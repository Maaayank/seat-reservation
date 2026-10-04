package com.paytm.seats.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;

class ApiExceptionHandlerTests {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	@Test
	void clientThatHungUpGetsNothingWritten() {
		Exception ex = new HttpMessageNotWritableException("Could not write JSON",
				new ClientAbortException(new IOException("Broken pipe")));

		assertThat(this.handler.handleUnexpected(ex)).isNull();
	}

	@Test
	void realFaultIsA500() {
		ResponseEntity<ErrorResponse> response = this.handler.handleUnexpected(new IllegalStateException("boom"));

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody().error()).isEqualTo("internal_error");
	}

}
