package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Metrics must reconcile with the API: counters match what clients saw and
 * the seats gauge matches GET /shows/{id}.
 */
@IntegrationTest
class MetricsReconciliationTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	TestHttp http;

	ReservationFixtures fx;

	@BeforeEach
	void setUp() {
		this.http = new TestHttp(this.port);
		this.fx = new ReservationFixtures(this.http, this.jdbc);
	}

	@Test
	void countersAndGaugesMatchApiOutcomes() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(10), 25_000, 2);
		String alice = this.fx.token();
		String bob = this.fx.token();

		String key = UUID.randomUUID().toString();
		assertThat(this.fx.reserve(alice, showId, List.of("S1"), key).status()).isEqualTo(201);
		assertThat(this.fx.reserve(alice, showId, List.of("S1"), key).status()).isEqualTo(201); // replay
		assertThat(this.fx.reserve(alice, showId, List.of("S2"), key).status()).isEqualTo(409); // key reuse
		String rid = this.fx.reserveOk(alice, showId, List.of("S3"));
		assertThat(this.fx.reserve(alice, showId, List.of("S4"), key()).status()).isEqualTo(409); // limit
		assertThat(this.fx.reserve(bob, showId, List.of("S1"), key()).status()).isEqualTo(409); // taken
		assertThat(this.fx.reserve(bob, showId, List.of("NOPE"), key()).status()).isEqualTo(422); // invalid
		assertThat(this.fx.cancel(alice, rid).status()).isEqualTo(200);
		assertThat(this.fx.cancel(alice, rid).status()).isEqualTo(200); // repeat: not counted again

		String metrics = awaitGauge(showId, "confirmed", 1);
		assertThat(value(metrics, "reservations_confirmed_total", showId, null)).isEqualTo(2);
		assertThat(value(metrics, "reservations_cancelled_total", showId, null)).isEqualTo(1);
		assertThat(declined(metrics, showId, "idempotent_replay")).isEqualTo(1);
		assertThat(declined(metrics, showId, "idempotency_key_reuse")).isEqualTo(1);
		assertThat(declined(metrics, showId, "per_user_limit")).isEqualTo(1);
		assertThat(declined(metrics, showId, "seat_taken")).isEqualTo(1);
		assertThat(declined(metrics, showId, "invalid")).isEqualTo(1);
		assertGaugeMatchesApi(metrics, showId);
	}

	@Test
	void countersMatchApiAfterConcurrentBurst() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(20), 25_000, 4);
		List<String> tokens = new ArrayList<>(this.fx.tokens(300).values());
		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			String seat = "S" + (1 + (i % 20));
			tasks.add(() -> this.fx.reserve(token, showId, List.of(seat), key()));
		}
		Map<String, Long> outcomes = ReservationFixtures.outcomes(ReservationFixtures.concurrently(tasks));

		String metrics = awaitGauge(showId, "confirmed", 20);
		assertThat(value(metrics, "reservations_confirmed_total", showId, null)).isEqualTo(outcomes.get("201"));
		assertThat(declined(metrics, showId, "seat_taken")).isEqualTo(outcomes.get("409:seat_taken"));
		assertGaugeMatchesApi(metrics, showId);
	}

	private void assertGaugeMatchesApi(String metrics, String showId) {
		JsonNode counts = this.fx.show(showId).get("counts");
		assertThat(value(metrics, "seats", showId, "available")).isEqualTo(counts.get("available").asLong());
		assertThat(value(metrics, "seats", showId, "held")).isEqualTo(counts.get("held").asLong());
		assertThat(value(metrics, "seats", showId, "confirmed")).isEqualTo(counts.get("confirmed").asLong());
		assertThat(value(metrics, "seats_capacity", showId, null)).isEqualTo(counts.get("total").asLong());
	}

	/** Gauges refresh at most once per second after a write; wait for the expected value. */
	private String awaitGauge(String showId, String state, long expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
		while (true) {
			String metrics = this.http.get("/actuator/prometheus", Map.of()).body();
			if (value(metrics, "seats", showId, state) == expected || Instant.now().isAfter(deadline)) {
				return metrics;
			}
			Thread.sleep(100);
		}
	}

	private static long declined(String metrics, String showId, String reason) {
		Pattern p = Pattern.compile("^reservations_declined_total\\{[^}]*reason=\"" + reason + "\"[^}]*show_id=\""
				+ showId + "\"[^}]*} ([0-9.eE+-]+)$", Pattern.MULTILINE);
		Matcher m = p.matcher(metrics);
		return m.find() ? (long) Double.parseDouble(m.group(1)) : 0;
	}

	private static long value(String metrics, String name, String showId, String state) {
		String stateFilter = (state != null) ? "[^}]*state=\"" + state + "\"" : "";
		Pattern p = Pattern.compile("^" + name + "\\{[^}]*show_id=\"" + showId + "\"" + stateFilter + "[^}]*} ([0-9.eE+-]+)$",
				Pattern.MULTILINE);
		Matcher m = p.matcher(metrics);
		return m.find() ? (long) Double.parseDouble(m.group(1)) : -1;
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
