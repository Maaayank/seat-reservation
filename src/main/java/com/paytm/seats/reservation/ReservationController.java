package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import com.paytm.seats.common.ErrorResponse;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reserve and cancel. Both need a bearer token (BearerTokenFilter); the user comes only
 * from the token, never from the body.
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
	 * 201 for a new reservation, and also for an idempotent replay (same key, same
	 * request), which returns the original body with {@code Idempotent-Replayed: true}. A
	 * decline is written here as the standard error body, without throwing.
	 */
	@PostMapping("/shows/{showId}/reserve")
	ResponseEntity<?> reserve(AuthenticatedUser user, @PathVariable String showId,
			@RequestBody(required = false) ReserveRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		return switch (this.reservations.reserve(user, showId, request, idempotencyKey)) {
			case ReserveOutcome.Confirmed confirmed -> created(confirmed.reservation(), false);
			case ReserveOutcome.Replayed replayed -> created(replayed.reservation(), true);
			case ReserveOutcome.Declined declined -> ResponseEntity.status(declined.error().status())
				.body(ErrorResponse.of(declined.error(), declined.message()));
		};
	}

	private static ResponseEntity<ReservationView> created(ReservationView reservation, boolean replayed) {
		return ResponseEntity.created(URI.create("/reservations/" + reservation.reservationId()))
			.header("Idempotent-Replayed", Boolean.toString(replayed))
			.body(reservation);
	}

	/**
	 * 200 with the reservation in status {@code cancelled}. A repeat cancel returns the
	 * same body.
	 */
	@PostMapping("/reservations/{reservationId}/cancel")
	ReservationView cancel(AuthenticatedUser user, @PathVariable String reservationId) {
		return this.cancels.cancel(user, reservationId);
	}

}
