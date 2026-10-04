package com.paytm.seats.reservation;

import com.paytm.seats.auth.AuthenticatedUser;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

	static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

	static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final ReservationService service;

	public ReservationController(ReservationService service) {
		this.service = service;
	}

	/**
	 * Reserve seats for the token's user. 201 on success, and also on an
	 * idempotent replay (same key, same request), which returns the original
	 * reservation with {@code Idempotent-Replayed: true}.
	 */
	@PostMapping("/shows/{showId}/reserve")
	public ResponseEntity<ReservationView> reserve(AuthenticatedUser user, @PathVariable String showId,
			@RequestBody(required = false) ReserveRequest request,
			@RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey) {
		ReservationService.ReserveResult result = this.service.reserve(user, showId, request, idempotencyKey);
		ReservationView view = result.reservation();
		return ResponseEntity.created(URI.create("/reservations/" + view.reservationId()))
			.header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
			.body(view);
	}

}
