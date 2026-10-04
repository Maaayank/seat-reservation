package com.paytm.seats.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds a {@link ProcessingSlots processing slot} for the whole request, so a waiting
 * request parks here, at the top of the stack, where it costs little memory.
 *
 * <p>
 * Skipped by health and metrics endpoints (probes keep working under load) and by
 * {@code POST /shows/{id}/reserve}, which takes its slot only after the checks it can do
 * in memory.
 *
 * <p>
 * Runs after the admin-key and bearer-token filters: a request with a bad or missing
 * credential gets its 401/403 at once instead of queueing behind valid requests.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class ConcurrencyLimitFilter extends OncePerRequestFilter {

	private static final Pattern RESERVE = Pattern.compile("/shows/[^/]+/reserve");

	private final ProcessingSlots slots;

	ConcurrencyLimitFilter(ProcessingSlots slots) {
		this.slots = slots;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri.startsWith("/actuator") || uri.equals("/livez") || uri.equals("/readyz")
				|| ("POST".equals(request.getMethod()) && RESERVE.matcher(uri).matches());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try (ProcessingSlots.Slot slot = this.slots.acquire()) {
			chain.doFilter(request, response);
		}
	}

}
