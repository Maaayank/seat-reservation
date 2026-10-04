package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The correctness bar from the problem statement, under real concurrency
 * against real Postgres. All requests in a test start at the same instant.
 */
@IntegrationTest
class ReservationConcurrencyTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	ReservationFixtures fx;

	@BeforeEach
	void setUp() {
		this.fx = new ReservationFixtures(new TestHttp(this.port), this.jdbc);
	}

	@Test
	void hotSeatHasExactlyOneWinner() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(10), 25_000, 4);
		Map<String, String> users = this.fx.tokens(500);

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		users.values().forEach((token) -> tasks.add(() -> this.fx.reserve(token, showId, List.of("S1"), key())));
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(ReservationFixtures.outcomes(responses)).containsExactlyInAnyOrderEntriesOf(
				Map.of("201", 1L, "409:seat_taken", 499L));
		String winner = responses.stream()
			.filter((r) -> r.status() == 201)
			.findFirst()
			.orElseThrow()
			.json()
			.get("user_id")
			.asString();
		String owner = this.jdbc.sql("SELECT owner_user_id FROM seats WHERE show_id = ? AND label = 'S1'")
			.param(UUID.fromString(showId))
			.query(String.class)
			.single();
		assertThat(owner).isEqualTo(winner);
		this.fx.assertInvariants(showId);
	}

	@Test
	void perUserLimitHoldsUnderParallelRequests() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(20), 25_000, 4);
		String token = this.fx.token();

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			String seat = "S" + i;
			tasks.add(() -> this.fx.reserve(token, showId, List.of(seat), key()));
		}
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(ReservationFixtures.outcomes(responses)).containsExactlyInAnyOrderEntriesOf(
				Map.of("201", 4L, "409:per_user_limit", 6L));
		assertThat(this.fx.show(showId).get("counts").get("confirmed").asInt()).isEqualTo(4);
		this.fx.assertInvariants(showId);
	}

	@Test
	void parallelRetriesWithSameKeyReserveOnce() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(10), 25_000, 4);
		String token = this.fx.token();
		String key = key();

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			tasks.add(() -> this.fx.reserve(token, showId, List.of("S1", "S2"), key));
		}
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(ReservationFixtures.outcomes(responses)).containsExactlyEntriesOf(Map.of("201", 50L));
		Set<String> reservationIds = responses.stream()
			.map((r) -> r.json().get("reservation_id").asString())
			.collect(Collectors.toSet());
		assertThat(reservationIds).hasSize(1);
		assertThat(responses.stream().filter((r) -> r.raw().headers().firstValue("Idempotent-Replayed")
			.orElse("").equals("false"))).hasSize(1);
		long reservations = this.jdbc.sql("SELECT count(*) FROM reservations WHERE show_id = ?")
			.param(UUID.fromString(showId))
			.query(Long.class)
			.single();
		assertThat(reservations).isEqualTo(1);

		TestHttp.Response differentBody = this.fx.reserve(token, showId, List.of("S3"), key);
		assertThat(ReservationFixtures.outcome(differentBody)).isEqualTo("409:idempotency_key_reuse");
		this.fx.assertInvariants(showId);
	}

	@Test
	void overlappingMultiSeatRequestsNeverDeadlockOrSplit() throws Exception {
		int pairs = 50;
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(pairs * 2), 25_000, 4);
		List<String> tokens = new ArrayList<>(this.fx.tokens(pairs * 2).values());

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int p = 0; p < pairs; p++) {
			String a = "S" + (2 * p + 1);
			String b = "S" + (2 * p + 2);
			String first = tokens.get(2 * p);
			String second = tokens.get(2 * p + 1);
			// Opposite request order: a naive lock order would deadlock here.
			tasks.add(() -> this.fx.reserve(first, showId, List.of(a, b), key()));
			tasks.add(() -> this.fx.reserve(second, showId, List.of(b, a), key()));
		}
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(ReservationFixtures.outcomes(responses)).containsExactlyInAnyOrderEntriesOf(
				Map.of("201", (long) pairs, "409:seat_taken", (long) pairs));
		assertThat(this.fx.show(showId).get("counts").get("confirmed").asInt()).isEqualTo(pairs * 2);
		this.fx.assertInvariants(showId);
	}

	@Test
	void mixedStampedeKeepsInvariants() throws Exception {
		String showId = this.fx.createShow(ReservationFixtures.seatLabels(50), 25_000, 4);
		List<String> tokens = new ArrayList<>(this.fx.tokens(400).values());

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			// Skewed demand: most users want the first five seats.
			int seat = (i % 4 == 0) ? 6 + (i % 45) : 1 + (i % 5);
			tasks.add(() -> this.fx.reserve(token, showId, List.of("S" + seat), key()));
		}
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		Map<String, Long> outcomes = ReservationFixtures.outcomes(responses);
		assertThat(outcomes).doesNotContainKey("5xx");
		long wins = outcomes.getOrDefault("201", 0L);
		assertThat(this.fx.show(showId).get("counts").get("confirmed").asLong()).isEqualTo(wins);
		this.fx.assertInvariants(showId);
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
