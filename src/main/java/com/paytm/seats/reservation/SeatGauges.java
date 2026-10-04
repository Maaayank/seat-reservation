package com.paytm.seats.reservation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code seats{show_id,state}} and {@code seats_capacity{show_id}} gauges, read
 * from the DB so they always reconcile with {@code GET /shows/{id}}.
 *
 * <p>The DB is queried only when something changed (a reserve, cancel or new
 * show marks the gauges dirty), at most once per second, plus a slow safety
 * refresh. An idle service sends no queries, so a serverless DB can sleep.
 *
 * <p>Each series is registered once and updated in place. (Re-registering on
 * every refresh would let a scrape that lands mid-refresh miss series.)
 */
@Component
public class SeatGauges {

	private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

	private static final Duration SAFETY_REFRESH = Duration.ofMinutes(30);

	private final JdbcClient jdbc;

	private final MeterRegistry registry;

	private final ConcurrentHashMap<String, AtomicLong> values = new ConcurrentHashMap<>();

	private final AtomicBoolean dirty = new AtomicBoolean(true);

	private volatile Instant lastRefresh = Instant.EPOCH;

	SeatGauges(JdbcClient jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		this.registry = registry;
	}

	public void markDirty() {
		this.dirty.set(true);
	}

	@EventListener(ApplicationReadyEvent.class)
	void initialRefresh() {
		refreshIfNeeded();
	}

	@Scheduled(fixedDelay = 1_000)
	void refreshIfNeeded() {
		boolean stale = Instant.now().isAfter(this.lastRefresh.plus(SAFETY_REFRESH));
		if (!this.dirty.getAndSet(false) && !stale) {
			return;
		}
		try {
			refresh();
			this.lastRefresh = Instant.now();
		}
		catch (RuntimeException ex) {
			this.dirty.set(true);
			log.warn("seat gauge refresh failed: {}", ex.getMessage());
		}
	}

	private void refresh() {
		List<Row> rows = this.jdbc.sql("""
				SELECT s.id::text AS show_id, st.state, count(se.label) AS n, s.total_seats
				FROM shows s
				CROSS JOIN (VALUES ('AVAILABLE'), ('HELD'), ('CONFIRMED')) AS st(state)
				LEFT JOIN seats se
				  ON se.show_id = s.id
				 AND (CASE WHEN se.status = 'HELD' AND se.hold_expires_at <= now() THEN 'AVAILABLE'
				           ELSE se.status END) = st.state
				GROUP BY s.id, st.state, s.total_seats
				""")
			.query((rs, n) -> new Row(rs.getString("show_id"), rs.getString("state"), rs.getLong("n"),
					rs.getLong("total_seats")))
			.list();
		for (Row row : rows) {
			String state = row.state().toLowerCase(Locale.ROOT);
			gauge("seats", "Seats per show by state (from the DB)", row.showId(), state).set(row.count());
			if (state.equals("available")) {
				gauge("seats.capacity", "Total seats per show", row.showId(), null).set(row.total());
			}
		}
	}

	private AtomicLong gauge(String name, String description, String showId, String state) {
		String key = name + "|" + showId + "|" + state;
		return this.values.computeIfAbsent(key, (k) -> {
			AtomicLong value = new AtomicLong();
			Gauge.Builder<AtomicLong> builder = Gauge.builder(name, value, AtomicLong::get)
				.description(description)
				.tag("show_id", showId);
			if (state != null) {
				builder.tag("state", state);
			}
			builder.register(this.registry);
			return value;
		});
	}

	private record Row(String showId, String state, long count, long total) {
	}

}
