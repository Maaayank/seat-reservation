package com.paytm.seats.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation id to every request. Accepts a well-formed client
 * {@code X-Request-Id}, otherwise generates one. The id goes into the MDC (so
 * every log line carries it) and back to the client in the response header.
 * Also writes one access log line per request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";
	public static final String MDC_KEY = "request_id";

	/** Set by the bearer token filter; cleared here after the access log line. */
	private static final String USER_MDC_KEY = "user_id";

	private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
	private static final Logger ACCESS_LOG = LoggerFactory.getLogger("access");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(HEADER));
		long start = System.nanoTime();
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			long latencyMs = (System.nanoTime() - start) / 1_000_000;
			if (!isProbe(request)) {
				ACCESS_LOG.atInfo()
					.addKeyValue("method", request.getMethod())
					.addKeyValue("route", request.getRequestURI())
					.addKeyValue("status", response.getStatus())
					.addKeyValue("latency_ms", latencyMs)
					.log("request completed");
			}
			MDC.remove(MDC_KEY);
			MDC.remove(USER_MDC_KEY);
		}
	}

	static String resolveRequestId(String candidate) {
		if (candidate != null && VALID_ID.matcher(candidate).matches()) {
			return candidate;
		}
		return UUID.randomUUID().toString();
	}

	private static boolean isProbe(HttpServletRequest request) {
		String uri = request.getRequestURI();
		return uri.startsWith("/actuator") || uri.equals("/livez") || uri.equals("/readyz");
	}

}
