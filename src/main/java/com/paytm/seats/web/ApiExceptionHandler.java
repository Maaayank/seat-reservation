package com.paytm.seats.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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
 * Maps exceptions to the error contract. Domain outcomes are 4xx. Anything
 * unexpected stays a 500 so real faults remain visible.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
		return respond(ex.status(), ex.code(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleInvalid(MethodArgumentNotValidException ex) {
		FieldError field = ex.getBindingResult().getFieldError();
		String message = (field != null) ? field.getField() + ": " + field.getDefaultMessage() : "invalid request";
		return respond(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_request", message);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
		return respond(HttpStatus.BAD_REQUEST, "malformed_request", "request body is not valid JSON for this endpoint");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
		return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "use Content-Type: application/json");
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
		return respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", ex.getMessage());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
		return respond(HttpStatus.NOT_FOUND, "not_found", "no such endpoint");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("unhandled exception", ex);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected server error");
	}

	private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message) {
		return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
	}

}
