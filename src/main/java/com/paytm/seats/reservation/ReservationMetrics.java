package com.paytm.seats.reservation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Business counters required by the problem statement. Prometheus names:
 * {@code reservations_confirmed_total}, {@code reservations_declined_total{reason}},
 * {@code reservations_cancelled_total}. Counters live in memory and reset on
 * restart; the {@code seats} gauge ({@link SeatGauges}) is read from the DB and
 * is the source of truth for reconciliation.
 */
@Component
class ReservationMetrics {

	private final MeterRegistry registry;

	private final SeatGauges gauges;

	ReservationMetrics(MeterRegistry registry, SeatGauges gauges) {
		this.registry = registry;
		this.gauges = gauges;
	}

	void confirmed(UUID showId) {
		counter("reservations.confirmed", showId).increment();
		this.gauges.markDirty();
	}

	/**
	 * @param reason seat_taken, per_user_limit, idempotent_replay,
	 * idempotency_key_reuse or invalid
	 */
	void declined(UUID showId, String reason) {
		Counter.builder("reservations.declined")
			.description("Reserve requests that did not create a reservation, by reason")
			.tag("show_id", showId.toString())
			.tag("reason", reason)
			.register(this.registry)
			.increment();
	}

	void cancelled(UUID showId) {
		counter("reservations.cancelled", showId).increment();
		this.gauges.markDirty();
	}

	private Counter counter(String name, UUID showId) {
		return Counter.builder(name).tag("show_id", showId.toString()).register(this.registry);
	}

}
