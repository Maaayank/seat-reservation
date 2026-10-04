package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class CancelTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	ReservationFixtures fx;

	String showId;

	@BeforeEach
	void setUp() {
		this.fx = new ReservationFixtures(new TestHttp(this.port), this.jdbc);
		this.showId = this.fx.createShow(ReservationFixtures.seatLabels(100), 25_000, 4);
	}

	@Test
	void ownerCancelReleasesSeatsAndQuota() {
		String token = this.fx.token();
		String rid = this.fx.reserveOk(token, this.showId, List.of("S1", "S2"));

		TestHttp.Response response = this.fx.cancel(token, rid);

		assertThat(response.status()).isEqualTo(200);
		JsonNode body = response.json();
		assertThat(body.get("reservation_id").asString()).isEqualTo(rid);
		assertThat(body.get("status").asString()).isEqualTo("cancelled");
		assertThat(body.get("amount_paise").asLong()).isEqualTo(50_000);
		JsonNode counts = this.fx.show(this.showId).get("counts");
		assertThat(counts.get("available").asInt()).isEqualTo(100);
		assertThat(counts.get("confirmed").asInt()).isZero();
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void cancelIsIdempotent() {
		String token = this.fx.token();
		String rid = this.fx.reserveOk(token, this.showId, List.of("S1"));
		TestHttp.Response first = this.fx.cancel(token, rid);
		TestHttp.Response second = this.fx.cancel(token, rid);
		assertThat(second.status()).isEqualTo(200);
		assertThat(second.body()).isEqualTo(first.body());
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void onlyOwnerCanCancel() {
		String rid = this.fx.reserveOk(this.fx.token(), this.showId, List.of("S1"));
		TestHttp.Response response = this.fx.cancel(this.fx.token(), rid);
		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json().get("error").asString()).isEqualTo("reservation_not_found");
		assertThat(this.fx.show(this.showId).get("counts").get("confirmed").asInt()).isEqualTo(1);
	}

	@Test
	void unknownOrMalformedIdIs404AndTokenRequired() {
		String token = this.fx.token();
		assertThat(this.fx.cancel(token, UUID.randomUUID().toString()).status()).isEqualTo(404);
		assertThat(this.fx.cancel(token, "nope").status()).isEqualTo(404);
		TestHttp.Response noToken = new TestHttp(this.port).post("/reservations/" + UUID.randomUUID() + "/cancel", "",
				Map.of());
		assertThat(noToken.status()).isEqualTo(401);
	}

	@Test
	void releasedSeatIsCleanlyRebookable() {
		String owner = this.fx.token();
		String rid = this.fx.reserveOk(owner, this.showId, List.of("S1"));
		this.fx.cancel(owner, rid);

		String other = this.fx.token();
		this.fx.reserveOk(other, this.showId, List.of("S1"));
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void repeatCancelNeverResurrectsSeatSoldToSomeoneElse() {
		String alice = this.fx.token();
		String rid = this.fx.reserveOk(alice, this.showId, List.of("S1"));
		this.fx.cancel(alice, rid);
		String bobsReservation = this.fx.reserveOk(this.fx.token(), this.showId, List.of("S1"));

		assertThat(this.fx.cancel(alice, rid).status()).isEqualTo(200);

		String seatReservation = this.jdbc
			.sql("SELECT reservation_id::text FROM seats WHERE show_id = ? AND label = 'S1'")
			.param(UUID.fromString(this.showId))
			.query(String.class)
			.single();
		assertThat(seatReservation).isEqualTo(bobsReservation);
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void cancelRestoresPerUserLimit() {
		String token = this.fx.token();
		String rid = this.fx.reserveOk(token, this.showId, List.of("S1", "S2", "S3", "S4"));
		assertThat(ReservationFixtures.outcome(this.fx.reserve(token, this.showId, List.of("S5"), key())))
			.isEqualTo("409:per_user_limit");
		this.fx.cancel(token, rid);
		this.fx.reserveOk(token, this.showId, List.of("S5", "S6", "S7", "S8"));
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void parallelCancelsReleaseOnce() throws Exception {
		String token = this.fx.token();
		String rid = this.fx.reserveOk(token, this.showId, List.of("S1", "S2"));

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> this.fx.cancel(token, rid));
		}
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(responses).allSatisfy((r) -> assertThat(r.status()).isEqualTo(200));
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void cancelRacingRebookersNeverDoubleSells() throws Exception {
		int seats = 20;
		int rebookersPerSeat = 20;
		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		List<String> rebookers = new ArrayList<>(this.fx.tokens(seats * rebookersPerSeat).values());
		for (int s = 1; s <= seats; s++) {
			String seat = "S" + s;
			String owner = this.fx.token();
			String rid = this.fx.reserveOk(owner, this.showId, List.of(seat));
			tasks.add(() -> this.fx.cancel(owner, rid));
			for (int r = 0; r < rebookersPerSeat; r++) {
				String rebooker = rebookers.get((s - 1) * rebookersPerSeat + r);
				tasks.add(() -> this.fx.reserve(rebooker, this.showId, List.of(seat), key()));
			}
		}

		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		Map<String, Long> outcomes = ReservationFixtures.outcomes(responses);
		assertThat(outcomes).doesNotContainKey("5xx");
		assertThat(outcomes.get("200")).isEqualTo((long) seats);
		assertThat(outcomes.getOrDefault("201", 0L)).isLessThanOrEqualTo(seats);
		this.fx.assertInvariants(this.showId);
	}

	@Test
	void ownReserveRacingOwnCancelNeverDeadlocks() throws Exception {
		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		// Enough pairs that a wrong lock order deadlocks reliably (verified by mutation).
		for (int p = 0; p < 40; p++) {
			String a = "S" + (2 * p + 1);
			String b = "S" + (2 * p + 2);
			String token = this.fx.token();
			String rid = this.fx.reserveOk(token, this.showId, List.of(a, b));
			tasks.add(() -> this.fx.cancel(token, rid));
			tasks.add(() -> this.fx.reserve(token, this.showId, List.of(b, a), key()));
		}

		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(responses).noneSatisfy((r) -> assertThat(r.status()).isGreaterThanOrEqualTo(500));
		this.fx.assertInvariants(this.showId);
	}

	private static String key() {
		return UUID.randomUUID().toString();
	}

}
