package com.paytm.seats.web;

import com.paytm.seats.config.SeatsProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards admin endpoints with the {@code X-Admin-Key} header. Compares in
 * constant time. Missing or wrong key → 403 {@code forbidden}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdminKeyFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Admin-Key";

	private static final Set<String> ADMIN_ROUTES = Set.of("POST /shows", "POST /auth/tokens");

	private final byte[] adminKey;

	private final ObjectMapper mapper;

	public AdminKeyFilter(SeatsProperties properties, ObjectMapper mapper) {
		this.adminKey = properties.adminApiKey().getBytes(StandardCharsets.UTF_8);
		this.mapper = mapper;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !ADMIN_ROUTES.contains(request.getMethod() + " " + request.getRequestURI());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String provided = request.getHeader(HEADER);
		if (provided != null && MessageDigest.isEqual(this.adminKey, provided.getBytes(StandardCharsets.UTF_8))) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		this.mapper.writeValue(response.getOutputStream(), ErrorResponse.of("forbidden", "valid X-Admin-Key required"));
	}

}
