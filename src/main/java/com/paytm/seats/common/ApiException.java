package com.paytm.seats.common;

/**
 * A domain outcome that ends the request with a non-2xx response.
 * {@link ApiExceptionHandler} turns it into the error body.
 *
 * <p>
 * It records no stack trace: it is an expected answer (4xx), not a fault, and filling a
 * stack trace through the whole servlet stack costs CPU on every decline. Real faults are
 * other exceptions, which keep their stack trace and are logged as 500s.
 */
public class ApiException extends RuntimeException {

	private final ErrorCode error;

	public ApiException(ErrorCode error, String message) {
		super(message, null, false, false);
		this.error = error;
	}

	public ErrorCode error() {
		return this.error;
	}

}
