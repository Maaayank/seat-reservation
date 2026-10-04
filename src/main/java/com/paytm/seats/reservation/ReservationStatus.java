package com.paytm.seats.reservation;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Reservation state. Stored upper case in the DB, exposed lower case in the API. */
public enum ReservationStatus {

	CONFIRMED, CANCELLED;

	@JsonValue
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

}
