package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reserve and cancel. Both need a bearer token (BearerTokenFilter); the user
 * comes only from the token, never from the body.
 */
@RestController
class ReservationController {

	private final ReservationService reservations;

	private final CancelService cancels;

	ReservationController(ReservationService reservations, CancelService cancels) {
		this.reservations = reservations;
		this.cancels = cancels;
	}

	/**
	 * 201 for a new reservation, and also for an idempotent replay (same key,
	 * same request), which returns the original body with
	 * {@code Idempotent-Replayed: true}.
	 */
	@PostMapping("/shows/{showId}/reserve")
	ResponseEntity<ReservationView> reserve(AuthenticatedUser user, @PathVariable String showId,
			@RequestBody(required = false) ReserveRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		ReserveOutcome outcome = this.reservations.reserve(user, showId, request, idempotencyKey);
		ReservationView reservation = switch (outcome) {
			case ReserveOutcome.Confirmed confirmed -> confirmed.reservation();
			case ReserveOutcome.Replayed replayed -> replayed.reservation();
			case ReserveOutcome.Declined declined -> throw new IllegalStateException("declines are thrown");
		};
		return ResponseEntity.created(URI.create("/reservations/" + reservation.reservationId()))
			.header("Idempotent-Replayed", Boolean.toString(outcome instanceof ReserveOutcome.Replayed))
			.body(reservation);
	}

	/** 200 with the reservation in status {@code cancelled}. A repeat cancel returns the same body. */
	@PostMapping("/reservations/{reservationId}/cancel")
	ReservationView cancel(AuthenticatedUser user, @PathVariable String reservationId) {
		return this.cancels.cancel(user, reservationId);
	}

}
