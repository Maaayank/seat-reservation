package com.paytm.seats.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AuthApiTests {

	private static final Map<String, String> ADMIN = Map.of("X-Admin-Key", IntegrationTest.ADMIN_KEY);

	@Value("${local.server.port}")
	int port;

	TestHttp http;

	@BeforeEach
	void setUp() {
		this.http = new TestHttp(this.port);
	}

	@Test
	void mintsOneTokenPerUserInBulk() {
		TestHttp.Response response = this.http.post("/auth/tokens", """
				{"user_ids":["u1","u2","u1"]}
				""", ADMIN);

		assertThat(response.status()).isEqualTo(200);
		JsonNode body = response.json();
		assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
		assertThat(body.get("expires_in").asLong()).isEqualTo(3600);
		assertThat(body.get("tokens").propertyNames()).containsExactly("u1", "u2");
	}

	@Test
	void mintingRequiresAdminKey() {
		TestHttp.Response response = this.http.post("/auth/tokens", """
				{"user_ids":["u1"]}
				""", Map.of());
		assertThat(response.status()).isEqualTo(403);
	}

	@Test
	void mintingRejectsInvalidUserIds() {
		assertThat(this.http.post("/auth/tokens", """
				{"user_ids":[]}
				""", ADMIN).status()).isEqualTo(422);
		assertThat(this.http.post("/auth/tokens", """
				{"user_ids":["bad id"]}
				""", ADMIN).status()).isEqualTo(422);
	}

	@Test
	void identityComesFromToken() {
		String token = mint("alice");
		TestHttp.Response response = this.http.get("/auth/me", bearer(token));
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json().get("user_id").asString()).isEqualTo("alice");
	}

	@Test
	void missingOrInvalidTokenIs401() {
		TestHttp.Response missing = this.http.get("/auth/me", Map.of());
		assertThat(missing.status()).isEqualTo(401);
		assertThat(missing.json().get("error").asString()).isEqualTo("unauthenticated");
		assertThat(missing.raw().headers().firstValue("WWW-Authenticate")).hasValue("Bearer");

		assertThat(this.http.get("/auth/me", bearer("garbage")).status()).isEqualTo(401);
		assertThat(this.http.get("/auth/me", Map.of("Authorization", "Basic abc")).status()).isEqualTo(401);
	}

	@Test
	void reserveRouteIsProtected() {
		TestHttp.Response response = this.http.post("/shows/any/reserve", "{}", Map.of());
		assertThat(response.status()).isEqualTo(401);
	}

	@Test
	void showReadsArePublic() {
		assertThat(this.http.get("/shows/00000000-0000-0000-0000-000000000000", Map.of()).status()).isEqualTo(404);
	}

	private String mint(String userId) {
		TestHttp.Response response = this.http.post("/auth/tokens", "{\"user_ids\":[\"" + userId + "\"]}", ADMIN);
		return response.json().get("tokens").get(userId).asString();
	}

	private static Map<String, String> bearer(String token) {
		return Map.of("Authorization", "Bearer " + token);
	}

}
