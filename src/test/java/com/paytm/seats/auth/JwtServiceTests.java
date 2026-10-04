package com.paytm.seats.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.config.SeatsProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtServiceTests {

	private static final String SECRET = "unit-test-secret-0123456789abcdef-0123456789";

	private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

	private final JwtService service = service(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void issuedTokenVerifiesToSubject() {
		assertThat(this.service.verify(this.service.issue("u42"))).hasValue("u42");
	}

	@Test
	void rejectsTokenSignedWithOtherSecret() {
		String foreign = service("another-secret-0123456789abcdef-0123456789", Clock.fixed(NOW, ZoneOffset.UTC))
			.issue("u42");
		assertThat(this.service.verify(foreign)).isEmpty();
	}

	@Test
	void rejectsTamperedPayload() {
		String[] parts = this.service.issue("u42").split("\\.");
		String forgedPayload = Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString("{\"sub\":\"admin\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
		assertThat(this.service.verify(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
	}

	@Test
	void rejectsExpiredToken() {
		String token = this.service.issue("u42");
		JwtService later = service(SECRET, Clock.fixed(NOW.plus(Duration.ofHours(2)), ZoneOffset.UTC));
		assertThat(later.verify(token)).isEmpty();
	}

	@Test
	void rejectsUnsignedAlgNoneToken() {
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		String header = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		String payload = enc.encodeToString("{\"sub\":\"u42\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));
		assertThat(this.service.verify(header + "." + payload + ".")).isEmpty();
	}

	@Test
	void rejectsGarbage() {
		assertThat(this.service.verify("not-a-jwt")).isEmpty();
		assertThat(this.service.verify("")).isEmpty();
	}

	private static JwtService service(String secret, Clock clock) {
		SeatsProperties properties = new SeatsProperties("admin", secret, Duration.ofHours(1), 10_000, 4, 10_000, 64,
				new SeatsProperties.Layers(true, true, true, Duration.ofSeconds(5)));
		return new JwtService(properties, clock);
	}

}
