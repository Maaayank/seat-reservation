package com.paytm.seats.common;

/**
 * A domain outcome that ends the request with a non-2xx response.
 * {@link ApiExceptionHandler} turns it into the error body.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode error;

	public ApiException(ErrorCode error, String message) {
		super(message);
		this.error = error;
	}

	public ErrorCode error() {
		return this.error;
	}

}
