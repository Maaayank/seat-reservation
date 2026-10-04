package com.paytm.seats.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestIdFilterTests {

	@Test
	void keepsWellFormedId() {
		assertThat(RequestIdFilter.resolveRequestId("req-1.A_b")).isEqualTo("req-1.A_b");
	}

	@Test
	void generatesIdWhenMissing() {
		assertThat(RequestIdFilter.resolveRequestId(null)).hasSize(36);
	}

	@Test
	void rejectsUnsafeOrOversizedIds() {
		assertThat(RequestIdFilter.resolveRequestId("a\nb")).isNotEqualTo("a\nb");
		assertThat(RequestIdFilter.resolveRequestId("x".repeat(65))).hasSize(36);
	}

}
