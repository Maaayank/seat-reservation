package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import com.paytm.seats.show.Show;
import com.paytm.seats.show.ShowCatalog;
import com.paytm.seats.show.ShowService;
import com.paytm.seats.web.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The atomic seat decision. Validation runs before the transaction (no DB
 * locks). The transaction itself is T1 from docs/DISCOVERY.md §4.3.3: claim
 * the idempotency key, reserve per-user quota, lock the seats in label order,
 * then write. Any decline rolls the whole transaction back, including the key,
 * so a declined key can be retried.
 */
@Service
public class ReservationService {

	private static final Logger log = LoggerFactory.getLogger("reservation");

	private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	private final ShowCatalog catalog;

	private final ReservationRepository repository;

	private final TransactionTemplate transactions;

	private final ObjectMapper mapper;

	public ReservationService(ShowCatalog catalog, ReservationRepository repository, TransactionTemplate transactions,
			ObjectMapper mapper) {
		this.catalog = catalog;
		this.repository = repository;
		this.transactions = transactions;
		this.mapper = mapper;
	}

	public ReserveResult reserve(AuthenticatedUser user, String rawShowId, ReserveRequest request, String headerKey) {
		UUID showId = ShowService.parseShowId(rawShowId);
		ShowCatalog.Entry entry = this.catalog.find(showId)
			.orElseThrow(() -> ApiException.notFound("show_not_found", "show not found"));
		Show show = entry.show();
		String key = resolveKey(headerKey, request);
		List<String> seats = validateSeats(request, entry);
		if (seats.size() > show.perUserLimit()) {
			throw decline(ApiException.conflict("per_user_limit",
					"at most " + show.perUserLimit() + " seats per user for this show"), user, showId, seats);
		}

		String requestHash = RequestHash.of(showId, seats);
		String[] labels = seats.toArray(String[]::new);
		UUID reservationId = UUID.randomUUID();
		long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
		ReservationView view = new ReservationView(reservationId, showId, user.id(), seats, amount, "confirmed");

		ReserveResult result = this.transactions.execute((tx) -> {
			// 1. Idempotency key.
			if (!this.repository.claimIdempotencyKey(user.id(), key, requestHash, showId)) {
				tx.setRollbackOnly();
				return replay(user.id(), key, requestHash);
			}
			// 2. Per-user quota.
			if (!this.repository.reserveQuota(showId, user.id(), seats.size(), show.perUserLimit())) {
				tx.setRollbackOnly();
				return ReserveResult.declined(ApiException.conflict("per_user_limit",
						"at most " + show.perUserLimit() + " seats per user for this show"));
			}
			// 3. Lock seats in label order; all-or-nothing.
			List<ReservationRepository.LockedSeat> locked = this.repository.lockSeats(showId, labels);
			if (locked.size() != seats.size()) {
				tx.setRollbackOnly();
				return ReserveResult.declined(ApiException.unprocessable("invalid_seats", "unknown seat label"));
			}
			if (!locked.stream().allMatch(ReservationRepository.LockedSeat::free)) {
				tx.setRollbackOnly();
				return ReserveResult.declined(ApiException.conflict("seat_taken", "one or more seats are taken"));
			}
			// 4-6. Write reservation, seats and the replayable response.
			this.repository.insertReservation(reservationId, showId, user.id(), labels, amount);
			this.repository.confirmSeats(showId, labels, reservationId, user.id());
			this.repository.completeIdempotencyKey(user.id(), key, reservationId, this.mapper.writeValueAsString(view));
			return ReserveResult.confirmed(view);
		});

		Objects.requireNonNull(result);
		if (result.declined() != null) {
			throw decline(result.declined(), user, showId, seats);
		}
		log.atInfo()
			.addKeyValue("show_id", showId)
			.addKeyValue("seats", seats)
			.addKeyValue("outcome", result.replayed() ? "idempotent_replay" : "confirmed")
			.addKeyValue("reservation_id", result.reservation().reservationId())
			.log("reserve decided");
		return result;
	}

	/**
	 * Owner-only cancel. Lock order: reservation row → quota row → seat rows by
	 * label (same as reserve, so the two cannot deadlock). Idempotent: a second
	 * cancel returns the same cancelled reservation. Another user's reservation
	 * is reported as not found, so its existence is not revealed.
	 */
	public ReservationView cancel(AuthenticatedUser user, String rawReservationId) {
		UUID reservationId = parseReservationId(rawReservationId);
		ReservationView result = this.transactions.execute((tx) -> {
			ReservationRepository.StoredReservation stored = this.repository.lockReservation(reservationId)
				.filter((r) -> r.userId().equals(user.id()))
				.orElseThrow(ReservationService::reservationNotFound);
			if ("CANCELLED".equals(stored.status())) {
				return view(stored, "cancelled");
			}
			this.repository.releaseQuota(stored.showId(), stored.userId(), stored.seats().size());
			int released = this.repository.releaseSeats(stored.showId(), reservationId);
			if (released != stored.seats().size()) {
				throw new IllegalStateException("reservation " + reservationId + " owns " + released + " of "
						+ stored.seats().size() + " seats");
			}
			this.repository.markCancelled(reservationId);
			return view(stored, "cancelled");
		});
		Objects.requireNonNull(result);
		log.atInfo()
			.addKeyValue("show_id", result.showId())
			.addKeyValue("seats", result.seats())
			.addKeyValue("outcome", "cancelled")
			.addKeyValue("reservation_id", reservationId)
			.log("cancel decided");
		return result;
	}

	private static ReservationView view(ReservationRepository.StoredReservation stored, String status) {
		return new ReservationView(stored.id(), stored.showId(), stored.userId(), stored.seats(), stored.amountPaise(),
				status);
	}

	private static UUID parseReservationId(String raw) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException ex) {
			throw reservationNotFound();
		}
	}

	private static ApiException reservationNotFound() {
		return ApiException.notFound("reservation_not_found", "reservation not found");
	}

	private ReserveResult replay(String userId, String key, String requestHash) {
		ReservationRepository.StoredKey stored = this.repository.findIdempotencyKey(userId, key)
			.orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict"));
		if (!stored.requestHash().equals(requestHash)) {
			return ReserveResult.declined(ApiException.conflict("idempotency_key_reuse",
					"idempotency key was already used with a different request"));
		}
		if (stored.responseJson() == null) {
			throw new IllegalStateException("committed idempotency key has no stored response");
		}
		return ReserveResult.replayed(this.mapper.readValue(stored.responseJson(), ReservationView.class));
	}

	private static String resolveKey(String headerKey, ReserveRequest request) {
		String key = (headerKey != null && !headerKey.isBlank()) ? headerKey
				: (request != null) ? request.idempotencyKey() : null;
		if (key == null || key.isBlank()) {
			throw ApiException.badRequest("idempotency_key_required",
					"send an Idempotency-Key header or an idempotency_key field");
		}
		if (!KEY_FORMAT.matcher(key).matches()) {
			throw ApiException.unprocessable("invalid_request",
					"idempotency key must be 1-128 chars of [A-Za-z0-9._:-]");
		}
		return key;
	}

	private static List<String> validateSeats(ReserveRequest request, ShowCatalog.Entry entry) {
		List<String> seats = (request != null) ? request.seats() : null;
		if (seats == null || seats.isEmpty()) {
			throw ApiException.unprocessable("invalid_seats", "seats must not be empty");
		}
		if (seats.stream().anyMatch(Objects::isNull) || new HashSet<>(seats).size() != seats.size()) {
			throw ApiException.unprocessable("invalid_seats", "seats must be unique and non-null");
		}
		if (!entry.seatLabels().containsAll(seats)) {
			throw ApiException.unprocessable("invalid_seats", "unknown seat label for this show");
		}
		return List.copyOf(seats);
	}

	private static ApiException decline(ApiException reason, AuthenticatedUser user, UUID showId, List<String> seats) {
		log.atInfo()
			.addKeyValue("show_id", showId)
			.addKeyValue("seats", seats)
			.addKeyValue("outcome", "declined")
			.addKeyValue("reason", reason.code())
			.log("reserve decided");
		return reason;
	}

	/** Outcome of the transaction: exactly one of reservation or declined is set. */
	public record ReserveResult(ReservationView reservation, boolean replayed, ApiException declined) {

		static ReserveResult confirmed(ReservationView view) {
			return new ReserveResult(view, false, null);
		}

		static ReserveResult replayed(ReservationView view) {
			return new ReserveResult(view, true, null);
		}

		static ReserveResult declined(ApiException reason) {
			return new ReserveResult(null, false, reason);
		}

	}

}
