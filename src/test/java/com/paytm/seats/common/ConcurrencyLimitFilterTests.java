package com.paytm.seats.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.paytm.seats.auth.AdminKeyFilter;
import com.paytm.seats.auth.BearerTokenFilter;
import com.paytm.seats.config.SeatsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ConcurrencyLimitFilterTests {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final ConcurrencyLimitFilter filter = new ConcurrencyLimitFilter(properties(1), this.registry);

	@Test
	void secondRequestWaitsUntilFirstFinishes() throws Exception {
		CountDownLatch firstInside = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		AtomicInteger completed = new AtomicInteger();

		Thread first = Thread.ofVirtual().start(() -> run("/shows/x/reserve", () -> {
			firstInside.countDown();
			await(releaseFirst);
			completed.incrementAndGet();
		}));
		firstInside.await();
		Thread second = Thread.ofVirtual().start(() -> run("/shows/x/reserve", completed::incrementAndGet));

		waitForQueue(1);
		assertThat(completed).hasValue(0);
		releaseFirst.countDown();
		first.join();
		second.join();
		assertThat(completed).hasValue(2);
	}

	@Test
	void probesBypassTheLimit() throws Exception {
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch firstInside = new CountDownLatch(1);
		Thread first = Thread.ofVirtual().start(() -> run("/shows/x/reserve", () -> {
			firstInside.countDown();
			await(releaseFirst);
		}));
		firstInside.await();

		AtomicInteger probes = new AtomicInteger();
		run("/readyz", probes::incrementAndGet);
		run("/actuator/prometheus", probes::incrementAndGet);
		assertThat(probes).hasValue(2);

		releaseFirst.countDown();
		first.join();
	}

	@Test
	void runsAfterTheAuthFilters() {
		int order = OrderUtils.getOrder(ConcurrencyLimitFilter.class, 0);
		assertThat(order).isGreaterThan(OrderUtils.getOrder(AdminKeyFilter.class, 0))
			.isGreaterThan(OrderUtils.getOrder(BearerTokenFilter.class, 0));
	}

	private void run(String uri, Runnable insideChain) {
		try {
			this.filter.doFilter(new MockHttpServletRequest("POST", uri), new MockHttpServletResponse(),
					(request, response) -> insideChain.run());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void waitForQueue(int expected) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (this.registry.get("http.requests.waiting").gauge().value() < expected) {
			assertThat(System.nanoTime()).isLessThan(deadline);
			Thread.sleep(5);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static SeatsProperties properties(int maxConcurrent) {
		return new SeatsProperties("admin", "unit-test-secret-0123456789abcdef-0123456789", Duration.ofHours(1), 10_000,
				4, 10_000, maxConcurrent, new SeatsProperties.Layers(true, true, true, Duration.ofSeconds(5)));
	}

}
