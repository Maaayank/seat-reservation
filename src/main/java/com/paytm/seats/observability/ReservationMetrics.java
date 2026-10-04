package com.paytm.seats.observability;

import com.paytm.seats.common.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Business counters required by the problem statement:
 * <ul>
 * <li>{@code reservations_confirmed_total{show_id}}</li>
 * <li>{@code reservations_declined_total{show_id,reason}}: seat_taken, per_user_limit,
 * idempotent_replay, idempotency_key_reuse, invalid</li>
 * <li>{@code reservations_cancelled_total{show_id}}</li>
 * </ul>
 * Counters live in memory and reset on restart. The DB-backed {@link SeatGauges} are the
 * source of truth for reconciliation.
 */
@Component
public class ReservationMetrics {

	private final MeterRegistry registry;

	private final SeatGauges gauges;

	private final Map<Key, Counter> counters = new ConcurrentHashMap<>();

	ReservationMetrics(MeterRegistry registry, SeatGauges gauges) {
		this.registry = registry;
		this.gauges = gauges;
	}

	public void confirmed(UUID showId) {
		counter("reservations.confirmed", showId).increment();
		this.gauges.markDirty();
	}

	/**
	 * A retry that returned the original reservation. Counted as a decline: nothing new
	 * was sold.
	 */
	public void replayed(UUID showId) {
		declined(showId, "idempotent_replay");
	}

	public void declined(UUID showId, ErrorCode error) {
		declined(showId, switch (error) {
			case SEAT_TAKEN, PER_USER_LIMIT, IDEMPOTENCY_KEY_REUSE -> error.code();
			default -> "invalid";
		});
	}

	public void cancelled(UUID showId) {
		counter("reservations.cancelled", showId).increment();
		this.gauges.markDirty();
	}

	private void declined(UUID showId, String reason) {
		this.counters
			.computeIfAbsent(new Key("reservations.declined", showId, reason),
					(key) -> Counter.builder(key.name())
						.description("Reserve requests that did not create a reservation, by reason")
						.tag("show_id", showId.toString())
						.tag("reason", reason)
						.register(this.registry))
			.increment();
	}

	private Counter counter(String name, UUID showId) {
		return this.counters.computeIfAbsent(new Key(name, showId, null),
				(key) -> Counter.builder(name).tag("show_id", showId.toString()).register(this.registry));
	}

	/** Cache key: building and registering a counter on every request costs CPU. */
	private record Key(String name, UUID showId, String reason) {
	}

}
