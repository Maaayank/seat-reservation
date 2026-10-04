package com.paytm.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

@IntegrationTest
class HealthAndObservabilityTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@Value("${local.server.port}")
	int port;

	@Test
	void livenessIsUp() throws Exception {
		HttpResponse<String> response = get("/livez", null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("UP");
	}

	@Test
	void readinessIsUpWhenDatabaseIsReachable() throws Exception {
		HttpResponse<String> response = get("/readyz", null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("UP");
	}

	@Test
	void prometheusEndpointIsExposed() throws Exception {
		HttpResponse<String> response = get("/actuator/prometheus", null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("hikaricp_connections");
	}

	@Test
	void echoesWellFormedRequestId() throws Exception {
		HttpResponse<String> response = get("/livez", "abc-123");
		assertThat(response.headers().firstValue("X-Request-Id")).hasValue("abc-123");
	}

	@Test
	void replacesMalformedRequestId() throws Exception {
		HttpResponse<String> response = get("/livez", "bad id with spaces");
		assertThat(response.headers().firstValue("X-Request-Id")).isPresent().get().isNotEqualTo("bad id with spaces");
	}

	private HttpResponse<String> get(String path, String requestId) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (requestId != null) {
			builder.header("X-Request-Id", requestId);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
