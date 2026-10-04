package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import com.paytm.seats.common.ApiException;
import com.paytm.seats.common.ErrorCode;
import com.paytm.seats.observability.ReservationMetrics;
import com.paytm.seats.reservation.layers.DeclineLayers;
import com.paytm.seats.reservation.layers.SeatClaims;
import com.paytm.seats.show.Show;
import com.paytm.seats.show.ShowCatalog;
import com.paytm.seats.show.ShowService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reserve flow for one request:
 *
 * <pre>
 * validate (no DB)                         → 4xx on bad input or over-limit
 * fast-decline: L2 sold set, L1 read       → 409 seat_taken, no transaction
 * L3 per-seat claim (one DB attempt/seat)  → waiters re-check L2 when it is their turn
 * ReserveTransaction                       → the only step that grants seats
 * </pre>
 *
 * The layers only ever decline, and only for seats owned by another user, so an
 * idempotent retry always reaches the transaction and replays (D22). Every outcome is
 * counted in the metrics and logged once as "reserve decided".
 */
@Service
class ReservationService {

	private static final Logger log = LoggerFactory.getLogger("reservation");

	private final ShowCatalog catalog;

	private final DeclineLayers layers;

	private final ReserveTransaction transaction;

	private final ReservationMetrics metrics;

	ReservationService(ShowCatalog catalog, DeclineLayers layers, ReserveTransaction transaction,
			ReservationMetrics metrics) {
		this.catalog = catalog;
		this.layers = layers;
		this.transaction = transaction;
		this.metrics = metrics;
	}

	/**
	 * Returns a confirmed or replayed reservation; throws {@link ApiException} for every
	 * decline.
	 */
	ReserveOutcome reserve(AuthenticatedUser user, String rawShowId, ReserveRequest request, String headerKey) {
		UUID showId = ShowService.parseShowId(rawShowId);
		ShowCatalog.Entry entry = this.catalog.find(showId)
			.orElseThrow(() -> new ApiException(ErrorCode.SHOW_NOT_FOUND, "show not found"));
		ReserveOutcome outcome;
		List<String> seats = List.of();
		try {
			String key = ReserveRequestValidator.idempotencyKey(headerKey, request);
			seats = ReserveRequestValidator.seats(request, entry);
			outcome = decide(user, entry.show(), key, seats);
		}
		catch (ApiException ex) {
			outcome = new ReserveOutcome.Declined(ex.error(), ex.getMessage());
		}
		record(showId, seats, outcome);
		if (outcome instanceof ReserveOutcome.Declined declined) {
			throw new ApiException(declined.error(), declined.message());
		}
		return outcome;
	}

	private ReserveOutcome decide(AuthenticatedUser user, Show show, String key, List<String> seats) {
		if (this.layers.declineEarly(show.id(), seats, user.id())) {
			return seatTaken();
		}
		long amount = Math.multiplyExact(show.pricePaise(), (long) seats.size());
		ReservationView reservation = new ReservationView(UUID.randomUUID(), show.id(), user.id(), seats, amount,
				ReservationStatus.CONFIRMED);
		String requestHash = RequestHash.of(show.id(), seats);

		// The claim is held until the sold set is updated, so the next waiter sees the
		// result.
		try (SeatClaims.Claim claim = this.layers.claim(show.id(), seats.stream().sorted().toList())) {
			if (this.layers.declineAfterWait(claim, show.id(), seats, user.id())) {
				return seatTaken();
			}
			ReserveOutcome outcome = this.transaction.execute(show, key, requestHash, reservation);
			if (outcome instanceof ReserveOutcome.Confirmed) {
				this.layers.confirmed(show.id(), seats, reservation.reservationId(), user.id());
			}
			else if (outcome instanceof ReserveOutcome.Declined declined && declined.error() == ErrorCode.SEAT_TAKEN) {
				this.layers.declinedByDatabase();
			}
			return outcome;
		}
	}

	private static ReserveOutcome seatTaken() {
		return new ReserveOutcome.Declined(ErrorCode.SEAT_TAKEN, "one or more seats are taken");
	}

	/** One metric and one log line per reserve request. */
	private void record(UUID showId, List<String> seats, ReserveOutcome outcome) {
		var line = log.atInfo().addKeyValue("show_id", showId).addKeyValue("seats", seats);
		switch (outcome) {
			case ReserveOutcome.Confirmed confirmed -> {
				this.metrics.confirmed(showId);
				line.addKeyValue("outcome", "confirmed")
					.addKeyValue("reservation_id", confirmed.reservation().reservationId());
			}
			case ReserveOutcome.Replayed replayed -> {
				this.metrics.replayed(showId);
				line.addKeyValue("outcome", "idempotent_replay")
					.addKeyValue("reservation_id", replayed.reservation().reservationId());
			}
			case ReserveOutcome.Declined declined -> {
				this.metrics.declined(showId, declined.error());
				line.addKeyValue("outcome", "declined").addKeyValue("reason", declined.error().code());
			}
		}
		line.log("reserve decided");
	}

}
