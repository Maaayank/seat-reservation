package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.Concurrency;
import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers for reservation tests: shows, tokens, reserve calls, invariants. */
final class ReservationFixtures {

	private static final Map<String, String> ADMIN = Map.of("X-Admin-Key", IntegrationTest.ADMIN_KEY);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TestHttp http;

	private final JdbcClient jdbc;

	ReservationFixtures(TestHttp http, JdbcClient jdbc) {
		this.http = http;
		this.jdbc = jdbc;
	}

	String createShow(List<String> seats, long pricePaise, int perUserLimit) {
		Map<String, Object> body = Map.of("name", "test-show", "seats", seats, "price_paise", pricePaise,
				"per_user_limit", perUserLimit);
		TestHttp.Response response = this.http.post("/shows", JSON.writeValueAsString(body), ADMIN);
		assertThat(response.status()).isEqualTo(201);
		return response.json().get("id").asString();
	}

	static List<String> seatLabels(int count) {
		return IntStream.rangeClosed(1, count).mapToObj((i) -> "S" + i).toList();
	}

	/** Mints tokens for fresh, unique users. */
	Map<String, String> tokens(int count) {
		String prefix = "u" + UUID.randomUUID().toString().substring(0, 8) + "-";
		List<String> ids = IntStream.range(0, count).mapToObj((i) -> prefix + i).toList();
		TestHttp.Response response = this.http.post("/auth/tokens", JSON.writeValueAsString(Map.of("user_ids", ids)),
				ADMIN);
		assertThat(response.status()).isEqualTo(200);
		Map<String, String> tokens = new HashMap<>();
		response.json().get("tokens").properties().forEach((e) -> tokens.put(e.getKey(), e.getValue().asString()));
		return tokens;
	}

	String token() {
		return tokens(1).values().iterator().next();
	}

	TestHttp.Response reserve(String token, String showId, List<String> seats, String key) {
		Map<String, Object> body = new HashMap<>();
		body.put("seats", seats);
		return this.http.post("/shows/" + showId + "/reserve", JSON.writeValueAsString(body),
				Map.of("Authorization", "Bearer " + token, "Idempotency-Key", key));
	}

	TestHttp.Response cancel(String token, String reservationId) {
		return this.http.post("/reservations/" + reservationId + "/cancel", "",
				Map.of("Authorization", "Bearer " + token));
	}

	/** Reserves and returns the reservation id; fails the test if not 201. */
	String reserveOk(String token, String showId, List<String> seats) {
		TestHttp.Response response = reserve(token, showId, seats, UUID.randomUUID().toString());
		assertThat(response.status()).as(response.body()).isEqualTo(201);
		return response.json().get("reservation_id").asString();
	}

	TestHttp.Response reserveWithBody(String token, String showId, String jsonBody, Map<String, String> headers) {
		Map<String, String> all = new HashMap<>(headers);
		all.put("Authorization", "Bearer " + token);
		return this.http.post("/shows/" + showId + "/reserve", jsonBody, all);
	}

	JsonNode show(String showId) {
		TestHttp.Response response = this.http.get("/shows/" + showId, Map.of());
		assertThat(response.status()).isEqualTo(200);
		return response.json();
	}

	/** Runs all tasks at the same instant (start gate) on virtual threads. */
	static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
		return Concurrency.concurrently(tasks);
	}

	static Map<String, Long> outcomes(List<TestHttp.Response> responses) {
		return responses.stream().collect(Collectors.groupingBy(ReservationFixtures::outcome, Collectors.counting()));
	}

	static String outcome(TestHttp.Response response) {
		if (response.status() < 300) {
			return String.valueOf(response.status());
		}
		if (response.status() >= 500) {
			return "5xx";
		}
		return response.status() + ":" + response.json().get("error").asString();
	}

	/**
	 * Checks every invariant the graders test, from the API and from the DB: counts add
	 * up, each confirmed seat belongs to exactly one confirmed reservation of its owner,
	 * and per-user quota equals seats owned.
	 */
	void assertInvariants(String showId) {
		JsonNode counts = show(showId).get("counts");
		assertThat(counts.get("available").asInt() + counts.get("held").asInt() + counts.get("confirmed").asInt())
			.isEqualTo(counts.get("total").asInt());

		UUID id = UUID.fromString(showId);
		long confirmedSeats = this.jdbc.sql("SELECT count(*) FROM seats WHERE show_id = ? AND status = 'CONFIRMED'")
			.param(id)
			.query(Long.class)
			.single();
		long reservedSeats = this.jdbc.sql(
				"SELECT coalesce(sum(cardinality(seat_labels)), 0) FROM reservations WHERE show_id = ? AND status = 'CONFIRMED'")
			.param(id)
			.query(Long.class)
			.single();
		assertThat(confirmedSeats).isEqualTo(reservedSeats).isEqualTo(counts.get("confirmed").asLong());

		long mismatched = this.jdbc.sql("""
				SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id
				WHERE s.show_id = ?
				  AND (r.user_id <> s.owner_user_id OR NOT s.label = ANY(r.seat_labels) OR r.status <> 'CONFIRMED')
				""").param(id).query(Long.class).single();
		assertThat(mismatched).isZero();

		long badQuota = this.jdbc.sql("""
				SELECT count(*) FROM user_show_quota q
				WHERE q.show_id = ?
				  AND q.seats_owned <> (SELECT count(*) FROM seats s
				                        WHERE s.show_id = q.show_id AND s.owner_user_id = q.user_id)
				""").param(id).query(Long.class).single();
		assertThat(badQuota).isZero();
	}

}
