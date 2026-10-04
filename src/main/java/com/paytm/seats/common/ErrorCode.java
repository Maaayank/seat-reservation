package com.paytm.seats.common;

import java.util.Locale;
import org.springframework.http.HttpStatus;

/**
 * Every error the API can return: the HTTP status and the stable {@code error} code
 * clients see. This is the error contract (docs/DISCOVERY.md §4.8) in one place.
 */
public enum ErrorCode {

	// @formatter:off

	// Reservation outcomes: clean domain declines, never 5xx.
	SEAT_TAKEN(HttpStatus.CONFLICT),
	PER_USER_LIMIT(HttpStatus.CONFLICT),
	IDEMPOTENCY_KEY_REUSE(HttpStatus.CONFLICT),

	// Request problems.
	IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	INVALID_REQUEST(HttpStatus.UNPROCESSABLE_CONTENT),
	INVALID_SEATS(HttpStatus.UNPROCESSABLE_CONTENT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

	// Lookups. Another user's reservation is also "not found", so its existence is not revealed.
	NOT_FOUND(HttpStatus.NOT_FOUND),
	SHOW_NOT_FOUND(HttpStatus.NOT_FOUND),
	RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND),

	// Access.
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN),

	// Anything unexpected. Kept as 500 on purpose so real faults stay visible.
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	// @formatter:on

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return this.status;
	}

	/** The value of the {@code error} field, e.g. {@code seat_taken}. */
	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}

}
