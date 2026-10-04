package com.paytm.seats.show;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ShowApiTests {

	private static final Map<String, String> ADMIN = Map.of("X-Admin-Key", IntegrationTest.ADMIN_KEY);

	@Value("${local.server.port}")
	int port;

	TestHttp http;

	@BeforeEach
	void setUp() {
		this.http = new TestHttp(this.port);
	}

	@Test
	void createsShowWithAllSeatsAvailable() {
		TestHttp.Response response = this.http.post("/shows", """
				{"name":"friday-night","seats":["A1","A2","A10"],"price_paise":25000}
				""", ADMIN);

		assertThat(response.status()).isEqualTo(201);
		JsonNode body = response.json();
		String id = body.get("id").asString();
		assertThat(response.raw().headers().firstValue("Location")).hasValue("/shows/" + id);
		assertThat(body.get("name").asString()).isEqualTo("friday-night");
		assertThat(body.get("price_paise").asLong()).isEqualTo(25000);
		assertThat(body.get("per_user_limit").asInt()).isEqualTo(4);
		assertThat(body.get("total_seats").asInt()).isEqualTo(3);
		assertCounts(body, 3, 0, 0, 3);
		assertThat(body.get("seats")).hasSize(3);
		assertThat(body.get("seats").get(0).get("status").asString()).isEqualTo("available");
	}

	@Test
	void getReturnsSeatsInCreationOrderWithCounts() {
		String id = createShow("""
				{"name":"g","seats":["A1","A2","A10","B1"],"price_paise":100,"per_user_limit":2}
				""");

		TestHttp.Response response = this.http.get("/shows/" + id, Map.of());

		assertThat(response.status()).isEqualTo(200);
		JsonNode body = response.json();
		assertThat(body.get("per_user_limit").asInt()).isEqualTo(2);
		assertThat(body.get("seats").valueStream().map((s) -> s.get("label").asString())).containsExactly("A1", "A2",
				"A10", "B1");
		assertCounts(body, 4, 0, 0, 4);
	}

	@Test
	void getUnknownShowIs404() {
		TestHttp.Response response = this.http.get("/shows/" + UUID.randomUUID(), Map.of());
		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json().get("error").asString()).isEqualTo("show_not_found");
		assertThat(response.json().get("request_id").asString()).isNotBlank();
	}

	@Test
	void getMalformedIdIs404() {
		assertThat(this.http.get("/shows/not-a-uuid", Map.of()).status()).isEqualTo(404);
	}

	@Test
	void createWithoutAdminKeyIs403() {
		String body = """
				{"name":"x","seats":["A1"],"price_paise":1}
				""";
		assertThat(this.http.post("/shows", body, Map.of()).status()).isEqualTo(403);
		TestHttp.Response wrong = this.http.post("/shows", body, Map.of("X-Admin-Key", "nope"));
		assertThat(wrong.status()).isEqualTo(403);
		assertThat(wrong.json().get("error").asString()).isEqualTo("forbidden");
	}

	@Test
	void rejectsDuplicateSeatLabels() {
		TestHttp.Response response = this.http.post("/shows", """
				{"name":"x","seats":["A1","A1"],"price_paise":1}
				""", ADMIN);
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.json().get("error").asString()).isEqualTo("invalid_seats");
	}

	@Test
	void rejectsInvalidBodies() {
		assertThat(post("""
				{"name":"x","seats":[],"price_paise":1}
				""")).isEqualTo(422);
		assertThat(post("""
				{"name":"x","seats":["A 1"],"price_paise":1}
				""")).isEqualTo(422);
		assertThat(post("""
				{"name":"x","seats":["A1"],"price_paise":-1}
				""")).isEqualTo(422);
		assertThat(post("""
				{"name":"","seats":["A1"],"price_paise":1}
				""")).isEqualTo(422);
		assertThat(post("""
				{"name":"x","seats":["A1"],"price_paise":1,"per_user_limit":0}
				""")).isEqualTo(422);
		assertThat(post("""
				{"name":"x","seats":["A1"]}
				""")).isEqualTo(422);
		assertThat(post("{not json")).isEqualTo(400);
	}

	@Test
	void rejectsFractionalPaise() {
		TestHttp.Response response = this.http.post("/shows", """
				{"name":"x","seats":["A1"],"price_paise":250.5}
				""", ADMIN);
		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("error").asString()).isEqualTo("malformed_request");
	}

	private int post(String body) {
		return this.http.post("/shows", body, ADMIN).status();
	}

	private String createShow(String body) {
		TestHttp.Response response = this.http.post("/shows", body, ADMIN);
		assertThat(response.status()).isEqualTo(201);
		return response.json().get("id").asString();
	}

	private static void assertCounts(JsonNode body, int available, int held, int confirmed, int total) {
		JsonNode counts = body.get("counts");
		assertThat(counts.get("available").asInt()).isEqualTo(available);
		assertThat(counts.get("held").asInt()).isEqualTo(held);
		assertThat(counts.get("confirmed").asInt()).isEqualTo(confirmed);
		assertThat(counts.get("total").asInt()).isEqualTo(total);
	}

}
