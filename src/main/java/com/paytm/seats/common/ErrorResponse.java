package com.paytm.seats.common;

import org.slf4j.MDC;

/** Body of every non-2xx response: {@code {"error", "message", "request_id"}}. */
public record ErrorResponse(String error, String message, String requestId) {

	public static ErrorResponse of(ErrorCode error, String message) {
		return new ErrorResponse(error.code(), message, MDC.get(RequestIdFilter.MDC_KEY));
	}

}
