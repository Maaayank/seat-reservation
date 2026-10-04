package com.paytm.seats.common;

import com.paytm.seats.config.SeatsProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets at most {@code seats.max-concurrent-requests} API requests run at once; the rest
 * wait here, in arrival order (fair semaphore).
 *
 * <p>
 * Why: with virtual threads every accepted request runs until it blocks, and a parked
 * virtual thread keeps its stack on the heap. Thousands of requests blocked deep in the
 * call stack (waiting for a DB connection or a seat claim) filled a 512 MB instance and
 * crashed it. Waiting here, at the top of the stack, costs very little memory.
 *
 * <p>
 * Nothing is rejected: no 503 or 429. A request waits for its turn. Health and metrics
 * endpoints bypass the limit so probes keep working under load.
 *
 * <p>
 * Runs after the admin-key and bearer-token filters: a request with a bad or missing
 * credential gets its 401/403 at once instead of queueing behind valid requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

	private final Semaphore permits;

	ConcurrencyLimitFilter(SeatsProperties properties, MeterRegistry registry) {
		this.permits = new Semaphore(properties.maxConcurrentRequests(), true);
		Gauge.builder("http.requests.waiting", this.permits, Semaphore::getQueueLength)
			.description("Requests waiting for a processing slot")
			.register(registry);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri.startsWith("/actuator") || uri.equals("/livez") || uri.equals("/readyz");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			this.permits.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ServletException("interrupted while waiting for a processing slot", ex);
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			this.permits.release();
		}
	}

}
