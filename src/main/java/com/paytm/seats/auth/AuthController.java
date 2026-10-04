package com.paytm.seats.auth;

import com.paytm.seats.config.SeatsProperties;
import com.paytm.seats.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

	private final JwtService jwt;

	private final SeatsProperties properties;

	public AuthController(JwtService jwt, SeatsProperties properties) {
		this.jwt = jwt;
		this.properties = properties;
	}

	/**
	 * Admin only (X-Admin-Key, enforced by AdminKeyFilter). Mints one token per
	 * user id in a single call, so a load test can create thousands of users.
	 */
	@PostMapping("/tokens")
	public TokensResponse mint(@Valid @RequestBody TokensRequest request) {
		if (request.userIds().size() > this.properties.maxTokensPerRequest()) {
			throw ApiException.unprocessable("invalid_request",
					"at most " + this.properties.maxTokensPerRequest() + " user_ids per request");
		}
		Map<String, String> tokens = new LinkedHashMap<>();
		for (String userId : request.userIds()) {
			tokens.computeIfAbsent(userId, this.jwt::issue);
		}
		return new TokensResponse("Bearer", this.jwt.ttl().toSeconds(), tokens);
	}

	/** Echoes the caller identity taken from the token. */
	@GetMapping("/me")
	public Map<String, String> me(AuthenticatedUser user) {
		return Map.of("user_id", user.id());
	}

	public record TokensRequest(@NotEmpty List<@NotNull @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}",
			message = "user ids must be 1-64 chars of [A-Za-z0-9._@-]") String> userIds) {
	}

	public record TokensResponse(String tokenType, long expiresIn, Map<String, String> tokens) {
	}

}
