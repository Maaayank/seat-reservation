package com.paytm.seats.reservation;

import java.util.List;
import java.util.UUID;

/** Reservation as returned by the API (201 body; stored verbatim for idempotent replay). */
public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise,
		String status) {
}
