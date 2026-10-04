package com.paytm.seats.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service settings. {@code adminApiKey} is required: the app does not start
 * without it, so a deploy can never run with an open admin surface.
 */
@Validated
@ConfigurationProperties("seats")
public record SeatsProperties(
		@NotBlank String adminApiKey,
		@Min(1) @Max(100_000) int maxSeatsPerShow,
		@Min(1) int defaultPerUserLimit) {

	public SeatsProperties {
		if (maxSeatsPerShow == 0) {
			maxSeatsPerShow = 10_000;
		}
		if (defaultPerUserLimit == 0) {
			defaultPerUserLimit = 4;
		}
	}

}
