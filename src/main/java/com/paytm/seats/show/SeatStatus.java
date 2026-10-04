package com.paytm.seats.show;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Seat state. Stored upper case in the DB, exposed lower case in the API. */
public enum SeatStatus {

	AVAILABLE, HELD, CONFIRMED;

	@JsonValue
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

}
