package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import com.paytm.seats.common.ProcessingSlots;
import com.paytm.seats.config.SeatsProperties;
import com.paytm.seats.reservation.layers.DeclineLayers;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * With layers on, the losers of a hot-seat storm are declined in the app, not by the
 * database: only a handful of requests reach the transaction. A request for a sold seat
 * does not even wait for a processing slot.
 */
@IntegrationTest
class DeclineLayersEffectTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MeterRegistry registry;

	@Autowired
	ProcessingSlots slots;

	@Autowired
	SeatsProperties properties;

	@Test
	void hotSeatLosersRarelyReachTheDatabase() throws Exception {
		ReservationFixtures fx = new ReservationFixtures(new TestHttp(this.port), this.jdbc);
		String showId = fx.createShow(ReservationFixtures.seatLabels(5), 25_000, 4);
		Map<String, String> users = fx.tokens(500);
		double dbBefore = declines("db");

		List<Callable<TestHttp.Response>> tasks = new ArrayList<>();
		users.values()
			.forEach((t) -> tasks.add(() -> fx.reserve(t, showId, List.of("S1"), UUID.randomUUID().toString())));
		List<TestHttp.Response> responses = ReservationFixtures.concurrently(tasks);

		assertThat(ReservationFixtures.outcomes(responses))
			.containsExactlyInAnyOrderEntriesOf(Map.of("201", 1L, "409:seat_taken", 499L));
		double dbDeclines = declines("db") - dbBefore;
		// Without layers this is 499. With L3 only requests already past the
		// claim when the winner committed can reach the transaction.
		assertThat(dbDeclines).isLessThan(10);
		fx.assertInvariants(showId);
	}

	@Test
	void soldSeatIsDeclinedWithoutWaitingForAProcessingSlot() throws Exception {
		ReservationFixtures fx = new ReservationFixtures(new TestHttp(this.port), this.jdbc);
		String showId = fx.createShow(ReservationFixtures.seatLabels(5), 25_000, 4);
		assertThat(fx.reserve(fx.token(), showId, List.of("S1"), UUID.randomUUID().toString()).status()).isEqualTo(201);
		String loser = fx.token();

		List<ProcessingSlots.Slot> held = new ArrayList<>();
		try {
			for (int i = 0; i < this.properties.maxConcurrentRequests(); i++) {
				held.add(this.slots.acquire());
			}
			TestHttp.Response response = CompletableFuture
				.supplyAsync(() -> fx.reserve(loser, showId, List.of("S1"), UUID.randomUUID().toString()))
				.get(10, TimeUnit.SECONDS);

			assertThat(ReservationFixtures.outcome(response)).isEqualTo("409:seat_taken");
		}
		finally {
			held.forEach(ProcessingSlots.Slot::close);
		}
	}

	private double declines(String layer) {
		return this.registry.get(DeclineLayers.METRIC).tag("layer", layer).counter().count();
	}

}
