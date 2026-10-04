package com.paytm.seats.web;

import org.slf4j.MDC;

/** Error body shared by every non-2xx response: {@code {error, message, request_id}}. */
public record ErrorResponse(String error, String message, String requestId) {

	public static ErrorResponse of(String error, String message) {
		return new ErrorResponse(error, message, MDC.get(RequestIdFilter.MDC_KEY));
	}

}
