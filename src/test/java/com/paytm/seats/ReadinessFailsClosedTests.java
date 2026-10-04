package com.paytm.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Uses its own database container so it can stop it without affecting other
 * tests. Readiness must fail closed (503) while liveness stays up (200).
 */
@Testcontainers
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.datasource.hikari.connection-timeout=1000",
			"seats.admin-api-key=" + IntegrationTest.ADMIN_KEY,
			"seats.jwt-secret=" + IntegrationTest.JWT_SECRET })
class ReadinessFailsClosedTests {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Value("${local.server.port}")
	int port;

	@Test
	void readinessFailsAndLivenessStaysUpWhenDatabaseIsDown() throws Exception {
		HttpClient http = HttpClient.newHttpClient();
		assertThat(status(http, "/readyz")).isEqualTo(200);

		postgres.stop();

		assertThat(status(http, "/readyz")).isEqualTo(503);
		assertThat(status(http, "/livez")).isEqualTo(200);
	}

	private int status(HttpClient http, String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
		return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

}
