package com.paytm.seats.reservation;

import com.paytm.seats.common.ErrorCode;

/** Result of one reserve attempt. Exactly one of three cases. */
sealed interface ReserveOutcome {

	/** A new reservation was created. */
	record Confirmed(ReservationView reservation) implements ReserveOutcome {
	}

	/**
	 * Same key, same request as an earlier success: the original reservation, unchanged.
	 */
	record Replayed(ReservationView reservation) implements ReserveOutcome {
	}

	/** Nothing changed. {@code error} says why (seat_taken, per_user_limit, ...). */
	record Declined(ErrorCode error, String message) implements ReserveOutcome {
	}

}
