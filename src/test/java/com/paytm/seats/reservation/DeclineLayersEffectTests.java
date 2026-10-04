package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.IntegrationTest;
import com.paytm.seats.TestHttp;
import com.paytm.seats.reservation.layers.DeclineLayers;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * With layers on, the losers of a hot-seat storm are declined in the app, not
 * by the database: only a handful of requests reach the transaction.
 */
@IntegrationTest
class DeclineLayersEffectTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MeterRegistry registry;

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

	private double declines(String layer) {
		return this.registry.get(DeclineLayers.METRIC).tag("layer", layer).counter().count();
	}

}
