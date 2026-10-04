package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import com.paytm.seats.common.ApiException;
import com.paytm.seats.common.ErrorCode;
import com.paytm.seats.observability.ReservationMetrics;
import com.paytm.seats.reservation.layers.DeclineLayers;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owner-only, idempotent cancel. One transaction that locks, in this order (same as
 * reserve, so the two cannot deadlock, D20):
 *
 * <ol>
 * <li>the reservation row (parallel cancels of it queue here);</li>
 * <li>the user's quota row (count goes down);</li>
 * <li>the seat rows still pointing at this reservation, by label (freed).</li>
 * </ol>
 *
 * A seat that already belongs to someone else is never released. Another user's
 * reservation is reported as not found, so its existence is not revealed.
 */
@Service
class CancelService {

	private static final Logger log = LoggerFactory.getLogger("reservation");

	private final ReservationRepository repository;

	private final TransactionTemplate transactions;

	private final DeclineLayers layers;

	private final ReservationMetrics metrics;

	CancelService(ReservationRepository repository, TransactionTemplate transactions, DeclineLayers layers,
			ReservationMetrics metrics) {
		this.repository = repository;
		this.transactions = transactions;
		this.layers = layers;
		this.metrics = metrics;
	}

	ReservationView cancel(AuthenticatedUser user, String rawReservationId) {
		UUID reservationId = parseId(rawReservationId);
		Cancelled result = Objects
			.requireNonNull(this.transactions.execute((tx) -> cancelInTransaction(user, reservationId)));
		ReservationView reservation = result.reservation();
		if (result.changed()) {
			this.layers.released(reservation.showId(), reservation.seats(), reservationId);
			this.metrics.cancelled(reservation.showId());
		}
		log.atInfo()
			.addKeyValue("show_id", reservation.showId())
			.addKeyValue("seats", reservation.seats())
			.addKeyValue("outcome", result.changed() ? "cancelled" : "already_cancelled")
			.addKeyValue("reservation_id", reservationId)
			.log("cancel decided");
		return reservation;
	}

	private Cancelled cancelInTransaction(AuthenticatedUser user, UUID reservationId) {
		ReservationView reservation = this.repository.lockReservation(reservationId)
			.filter((r) -> r.userId().equals(user.id()))
			.orElseThrow(CancelService::notFound);
		if (reservation.status() == ReservationStatus.CANCELLED) {
			return new Cancelled(reservation, false);
		}
		this.repository.removeFromQuota(reservation.showId(), reservation.userId(), reservation.seats().size());
		int released = this.repository.releaseSeats(reservation.showId(), reservationId);
		if (released != reservation.seats().size()) {
			throw new IllegalStateException("reservation " + reservationId + " owns " + released + " of "
					+ reservation.seats().size() + " seats");
		}
		this.repository.markCancelled(reservationId);
		return new Cancelled(reservation.withStatus(ReservationStatus.CANCELLED), true);
	}

	private static UUID parseId(String raw) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException ex) {
			throw notFound();
		}
	}

	private static ApiException notFound() {
		return new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "reservation not found");
	}

	/** {@code changed} is false for a repeat cancel. */
	private record Cancelled(ReservationView reservation, boolean changed) {
	}

}
