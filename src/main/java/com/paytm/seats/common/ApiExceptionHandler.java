package com.paytm.seats.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the error contract. Domain outcomes and bad requests are 4xx.
 * Anything unexpected is a 500 and is logged with its stack trace.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
		return respond(ex.error(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleInvalid(MethodArgumentNotValidException ex) {
		FieldError field = ex.getBindingResult().getFieldError();
		String message = (field != null) ? field.getField() + ": " + field.getDefaultMessage() : "invalid request";
		return respond(ErrorCode.INVALID_REQUEST, message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return respond(ErrorCode.MALFORMED_REQUEST, "request body is not valid JSON for this endpoint");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
		return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "use Content-Type: application/json");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
		return respond(ErrorCode.METHOD_NOT_ALLOWED, ex.getMessage());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
		return respond(ErrorCode.NOT_FOUND, "no such endpoint");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("unhandled exception", ex);
		return respond(ErrorCode.INTERNAL_ERROR, "unexpected server error");
	}

	private static ResponseEntity<ErrorResponse> respond(ErrorCode error, String message) {
		return ResponseEntity.status(error.status()).body(ErrorResponse.of(error, message));
	}

}
