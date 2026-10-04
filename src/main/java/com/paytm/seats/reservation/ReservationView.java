package com.paytm.seats.reservation;

import java.util.List;
import java.util.UUID;

/**
 * Reservation as returned by the API. The 201 body is stored verbatim with the
 * idempotency key, so a retry replays exactly the same bytes.
 */
public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise,
		ReservationStatus status) {

	ReservationView withStatus(ReservationStatus newStatus) {
		return new ReservationView(this.reservationId, this.showId, this.userId, this.seats, this.amountPaise, newStatus);
	}

}
