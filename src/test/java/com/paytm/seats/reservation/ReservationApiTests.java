package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class ReservationApiTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	ReservationFixtures fx;

	String showId;

	String token;

	@BeforeEach
	void setUp() {
		this.fx = new ReservationFixtures(new TestHttp(this.port), this.jdbc);
		this.showId = this.fx.createShow(ReservationFixtures.seatLabels(10), 25_000, 4);
		this.token = this.fx.token();
	}

	@Test
	void reservesSeatsAndReturnsConfirmedReservation() {
		TestHttp.Response response = this.fx.reserve(this.token, this.showId, List.of("S2", "S1"), key());

		assertThat(response.status()).isEqualTo(201);
		JsonNode body = response.json();
		assertThat(body.get("reservation_id").asString()).isNotBlank();
		assertThat(body.get("show_id").asString()).isEqualTo(this.showId);
		assertThat(body.get("user_id").asString()).isNotBlank();
		assertThat(body.get("seats").valueStream().map(JsonNode::asString)).containsExactly("S2", "S1");
		assertThat(body.get("amount_paise").asLong()).isEqualTo(50_000);
		assertThat(body.get("status").asString()).isEqualTo("confirmed");
		assertThat(response.raw().headers().firstValue("Idempotent-Replayed")).hasValue("false");

		JsonNode counts = this.fx.show(this.showId).get("counts");
		assertThat(counts.get("confirmed").asInt()).isEqualTo(2);
		assertThat(counts.get("available").asInt()).isEqualTo(8);
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void takenSeatIsCleanConflict() {
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1"), key()).status()).isEqualTo(201);
		TestHttp.Response response = this.fx.reserve(this.fx.token(), this.showId, List.of("S1"), key());
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("error").asString()).isEqualTo("seat_taken");
	}

	@Test
	void partialAvailabilityIsAllOrNothing() {
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1"), key()).status()).isEqualTo(201);

		TestHttp.Response response = this.fx.reserve(this.fx.token(), this.showId, List.of("S1", "S2"), key());

		assertThat(response.status()).isEqualTo(409);
		JsonNode seats = this.fx.show(this.showId).get("seats");
		assertThat(seats.get(1).get("status").asString()).isEqualTo("available");
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void requestOverLimitIsDeclined() {
		TestHttp.Response response = this.fx.reserve(this.token, this.showId, List.of("S1", "S2", "S3", "S4", "S5"),
				key());
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("error").asString()).isEqualTo("per_user_limit");
	}

	@Test
	void cumulativeLimitIsDeclined() {
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1", "S2", "S3"), key()).status()).isEqualTo(201);
		TestHttp.Response response = this.fx.reserve(this.token, this.showId, List.of("S4", "S5"), key());
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("error").asString()).isEqualTo("per_user_limit");
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S4"), key()).status()).isEqualTo(201);
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void retryWithSameKeyReplaysOriginalReservation() {
		String key = key();
		TestHttp.Response first = this.fx.reserve(this.token, this.showId, List.of("S1"), key);
		TestHttp.Response retry = this.fx.reserve(this.token, this.showId, List.of("S1"), key);

		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(retry.raw().headers().firstValue("Idempotent-Replayed")).hasValue("true");
		assertThat(reservationCount()).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentSeatsIsRejected() {
		String key = key();
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1"), key).status()).isEqualTo(201);
		TestHttp.Response response = this.fx.reserve(this.token, this.showId, List.of("S2"), key);
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("error").asString()).isEqualTo("idempotency_key_reuse");
	}

	@Test
	void sameKeyFromAnotherUserIsIndependent() {
		String key = key();
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1"), key).status()).isEqualTo(201);
		assertThat(this.fx.reserve(this.fx.token(), this.showId, List.of("S2"), key).status()).isEqualTo(201);
	}

	@Test
	void declinedKeyCanBeRetried() {
		String other = this.fx.token();
		assertThat(this.fx.reserve(other, this.showId, List.of("S1"), key()).status()).isEqualTo(201);
		String key = key();
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S1"), key).status()).isEqualTo(409);
		// The decline stored nothing, so the key is free for a fresh attempt.
		assertThat(this.fx.reserve(this.token, this.showId, List.of("S2"), key).status()).isEqualTo(201);
	}

	@Test
	void keyMayComeFromBody() {
		TestHttp.Response response = this.fx.reserveWithBody(this.token, this.showId, """
				{"seats":["S1"],"idempotency_key":"body-key-1"}
				""", Map.of());
		assertThat(response.status()).isEqualTo(201);
	}

	@Test
	void missingKeyIs400() {
		TestHttp.Response response = this.fx.reserveWithBody(this.token, this.showId, """
				{"seats":["S1"]}
				""", Map.of());
		assertThat(response.status()).isEqualTo(400);
		assertThat(response.json().get("error").asString()).isEqualTo("idempotency_key_required");
	}

	@Test
	void spoofedUserIdInBodyIsIgnored() {
		TestHttp.Response response = this.fx.reserveWithBody(this.token, this.showId, """
				{"seats":["S1"],"user_id":"victim","idempotency_key":"spoof-1"}
				""", Map.of());
		assertThat(response.status()).isEqualTo(201);
		String owner = response.json().get("user_id").asString();
		assertThat(owner).isNotEqualTo("victim").startsWith("u");
		String stored = this.jdbc.sql("SELECT owner_user_id FROM seats WHERE show_id = ? AND label = 'S1'")
			.param(UUID.fromString(this.showId))
			.query(String.class)
			.single();
		assertThat(stored).isEqualTo(owner);
	}

	@Test
	void invalidSeatRequestsAre422() {
		assertError(this.fx.reserve(this.token, this.showId, List.of(), key()), 422, "invalid_seats");
		assertError(this.fx.reserve(this.token, this.showId, List.of("S1", "S1"), key()), 422, "invalid_seats");
		assertError(this.fx.reserve(this.token, this.showId, List.of("NOPE"), key()), 422, "invalid_seats");
	}

	@Test
	void unknownShowIs404() {
		assertError(this.fx.reserve(this.token, UUID.randomUUID().toString(), List.of("S1"), key()), 404,
				"show_not_found");
	}

	@Test
	void requiresToken() {
		TestHttp.Response response = new TestHttp(this.port).post("/shows/" + this.showId + "/reserve",
				"{\"seats\":[\"S1\"]}", Map.of("Idempotency-Key", key()));
		assertThat(response.status()).isEqualTo(401);
	}

	private long reservationCount() {
		return this.jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?")
			.param(UUID.fromString(this.showId))
			.query(Long.class)
			.single();
	}

	private static void assertError(TestHttp.Response response, int status, String error) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.json().get("error").asString()).isEqualTo(error);
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
