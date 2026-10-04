package com.paytm.seats.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service settings. {@code adminApiKey} and {@code jwtSecret} are required:
 * the app does not start without them, so a deploy can never run with an open
 * admin surface or a guessable signing key.
 */
@Validated
@ConfigurationProperties("seats")
public record SeatsProperties(
		@NotBlank String adminApiKey,
		@NotBlank String jwtSecret,
		Duration jwtTtl,
		@Min(1) @Max(100_000) int maxSeatsPerShow,
		@Min(1) int defaultPerUserLimit,
		@Min(1) @Max(100_000) int maxTokensPerRequest) {

	public SeatsProperties {
		if (jwtTtl == null) {
			jwtTtl = Duration.ofHours(1);
		}
		if (maxSeatsPerShow == 0) {
			maxSeatsPerShow = 10_000;
		}
		if (defaultPerUserLimit == 0) {
			defaultPerUserLimit = 4;
		}
		if (maxTokensPerRequest == 0) {
			maxTokensPerRequest = 10_000;
		}
	}

	/** HS256 needs a key of at least 256 bits. */
	@AssertTrue(message = "seats.jwt-secret must be at least 32 bytes")
	public boolean isJwtSecretLongEnough() {
		return this.jwtSecret == null || this.jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
	}

}
