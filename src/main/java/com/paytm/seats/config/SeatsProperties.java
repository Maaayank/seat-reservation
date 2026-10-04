package com.paytm.seats.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Service settings ({@code seats.*}). The admin key and JWT secret have no default: the
 * app refuses to start without them, so a deploy can never run with an open admin surface
 * or a guessable signing key.
 */
@Validated
@ConfigurationProperties("seats")
// @formatter:off
public record SeatsProperties(
		@NotBlank String adminApiKey,
		@NotBlank String jwtSecret,
		@DefaultValue("1h") Duration jwtTtl,
		@DefaultValue("10000") @Min(1) @Max(100_000) int maxSeatsPerShow,
		@DefaultValue("4") @Min(1) int defaultPerUserLimit,
		@DefaultValue("10000") @Min(1) @Max(100_000) int maxTokensPerRequest,
		@DefaultValue("64") @Min(1) @Max(10_000) int maxConcurrentRequests,
		@DefaultValue @Valid Layers layers) {
// @formatter:on

	/** HS256 needs a key of at least 256 bits. */
	@AssertTrue(message = "seats.jwt-secret must be at least 32 bytes")
	public boolean isJwtSecretLongEnough() {
		return this.jwtSecret == null || this.jwtSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
	}

	/**
	 * Fast-decline layers (docs/DISCOVERY.md §6.2). L2 = in-memory sold set, L1 =
	 * non-locking read, L3 = one in-flight DB attempt per seat. Turning them off changes
	 * speed, never correctness.
	 */
	// @formatter:off
	public record Layers(
			@DefaultValue("true") boolean soldSet,
			@DefaultValue("true") boolean readCheck,
			@DefaultValue("true") boolean seatClaim,
			@DefaultValue("5s") Duration seatClaimTimeout) {
	// @formatter:on
	}

}
