package com.paytm.seats.common;

import com.paytm.seats.config.SeatsProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * At most {@code seats.max-concurrent-requests} requests do heavy work (DB calls, seat
 * claims) at once; the rest wait in arrival order (fair semaphore). Nothing is rejected.
 *
 * <p>
 * Why: with virtual threads every accepted request runs until it blocks, and a parked
 * virtual thread keeps its stack on the heap. Thousands of requests blocked on a DB
 * connection or a seat claim filled a 512 MB instance and crashed it. Waiting for a slot
 * costs far less memory.
 *
 * <p>
 * {@link ConcurrencyLimitFilter} takes a slot for most endpoints. The reserve path takes
 * one itself, after its in-memory checks, so a request it can decline without the DB is
 * answered at once instead of queueing behind seat sales.
 */
@Component
public class ProcessingSlots {

	private final Semaphore permits;

	ProcessingSlots(SeatsProperties properties, MeterRegistry registry) {
		this.permits = new Semaphore(properties.maxConcurrentRequests(), true);
		Gauge.builder("http.requests.waiting", this.permits, Semaphore::getQueueLength)
			.description("Requests waiting for a processing slot")
			.register(registry);
	}

	/** Waits for a slot. Close the returned slot to give it back. */
	public Slot acquire() {
		try {
			this.permits.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for a processing slot", ex);
		}
		return this.permits::release;
	}

	/** A held slot. */
	@FunctionalInterface
	public interface Slot extends AutoCloseable {

		@Override
		void close();

	}

}
