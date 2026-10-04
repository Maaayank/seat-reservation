package com.paytm.seats.auth;

import com.paytm.seats.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Requires a valid bearer token on user routes. On success the user is stored
 * as a request attribute (read via {@link AuthenticatedUser} parameters) and
 * in the MDC as {@code user_id}. Missing or invalid token → 401.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BearerTokenFilter extends OncePerRequestFilter {

	public static final String MDC_KEY = "user_id";

	private static final String PREFIX = "Bearer ";

	private static final Pattern USER_ROUTES = Pattern
		.compile("^(POST /shows/[^/]+/reserve|POST /reservations/[^/]+/cancel|GET /auth/me)$");

	private final JwtService jwt;

	private final ObjectMapper mapper;

	public BearerTokenFilter(JwtService jwt, ObjectMapper mapper) {
		this.jwt = jwt;
		this.mapper = mapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !USER_ROUTES.matcher(request.getMethod() + " " + request.getRequestURI()).matches();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<String> userId = extractToken(request).flatMap(this.jwt::verify);
		if (userId.isEmpty()) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			this.mapper.writeValue(response.getOutputStream(),
					ErrorResponse.of("unauthenticated", "valid bearer token required"));
			return;
		}
		request.setAttribute(AuthenticatedUser.REQUEST_ATTRIBUTE, new AuthenticatedUser(userId.get()));
		// RequestIdFilter (outermost) clears the MDC after the access log line.
		MDC.put(MDC_KEY, userId.get());
		chain.doFilter(request, response);
	}

	private static Optional<String> extractToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
			return Optional.empty();
		}
		String token = header.substring(PREFIX.length()).trim();
		return token.isEmpty() ? Optional.empty() : Optional.of(token);
	}

}
