package com.paytm.seats.burst;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client for the service. A semaphore caps requests in flight. A refused connect is
 * retried (the server never saw the request); a timeout is reported as an unknown
 * outcome, never as a server error.
 */
final class Api {

	static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient client;

	private final String baseUrl;

	private final String adminKey;

	private final Semaphore inFlight;

	private final Duration timeout;

	Api(String baseUrl, String adminKey, int maxInFlight, Duration timeout) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.adminKey = adminKey;
		this.inFlight = new Semaphore(maxInFlight, true);
		this.timeout = timeout;
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)
			.connectTimeout(Duration.ofSeconds(30))
			.build();
	}

	Result get(String path) {
		return send(HttpRequest.newBuilder(uri(path)).GET(), Map.of());
	}

	Result post(String path, Object body, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
		return send(builder, headers);
	}

	Result admin(String path, Object body) {
		return post(path, body, Map.of("X-Admin-Key", this.adminKey));
	}

	Result reserve(String token, String showId, List<String> seats, String key, Map<String, Object> extraBody) {
		Map<String, Object> body = new HashMap<>(extraBody);
		body.put("seats", seats);
		return post("/shows/" + showId + "/reserve", body,
				Map.of("Authorization", "Bearer " + token, "Idempotency-Key", key));
	}

	Result cancel(String token, String reservationId) {
		return post("/reservations/" + reservationId + "/cancel", Map.of(), Map.of("Authorization", "Bearer " + token));
	}

	/** Mints tokens in batches of 10,000 (the server's per-call limit). */
	Map<String, String> mintTokens(List<String> userIds) {
		Map<String, String> tokens = new LinkedHashMap<>();
		for (int from = 0; from < userIds.size(); from += 10_000) {
			List<String> batch = userIds.subList(from, Math.min(userIds.size(), from + 10_000));
			Result result = admin("/auth/tokens", Map.of("user_ids", batch));
			if (result.status() != 200) {
				throw new IllegalStateException("token mint failed: " + result.status() + " " + result.body());
			}
			result.json().get("tokens").properties().forEach((e) -> tokens.put(e.getKey(), e.getValue().asString()));
		}
		return tokens;
	}

	private URI uri(String path) {
		return URI.create(this.baseUrl + path);
	}

	private Result send(HttpRequest.Builder builder, Map<String, String> headers) {
		headers.forEach(builder::header);
		HttpRequest request = builder.timeout(this.timeout).build();
		try {
			this.inFlight.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Result.clientError("interrupted", 0);
		}
		long start = System.nanoTime();
		try {
			for (int attempt = 1;; attempt++) {
				try {
					HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
					return new Result(response.statusCode(), response.body(),
							response.headers().firstValue("Idempotent-Replayed").orElse(""), null, elapsedMs(start));
				}
				catch (ConnectException ex) {
					if (attempt == 5) {
						return Result.clientError("connect_refused", elapsedMs(start));
					}
					Thread.sleep(50L * attempt);
				}
			}
		}
		catch (HttpTimeoutException ex) {
			return Result.clientError("timeout", elapsedMs(start));
		}
		catch (IOException ex) {
			String message = (ex.getMessage() != null) ? ex.getMessage() : "";
			return Result.clientError(
					"io: " + ex.getClass().getSimpleName() + " " + message.substring(0, Math.min(60, message.length())),
					elapsedMs(start));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return Result.clientError("interrupted", elapsedMs(start));
		}
		finally {
			this.inFlight.release();
		}
	}

	private static double elapsedMs(long start) {
		return (System.nanoTime() - start) / 1_000_000.0;
	}

	/** One HTTP outcome. {@code clientError} is set when no HTTP status was received. */
	record Result(int status, String body, String replayed, String clientError, double latencyMs) {

		static Result clientError(String error, double latencyMs) {
			return new Result(0, "", "", error, latencyMs);
		}

		JsonNode json() {
			return JSON.readTree(this.body);
		}

		String reservationId() {
			return json().get("reservation_id").asString();
		}

		boolean isReplay() {
			return "true".equals(this.replayed);
		}

		/** Bucket for the report: 201, 409:seat_taken, 5xx, client:timeout ... */
		String outcome() {
			if (this.clientError != null) {
				return "client:" + this.clientError;
			}
			if (this.status >= 500) {
				// Our service always answers with a JSON error body carrying request_id.
				// Anything else came from a proxy in front of it (e.g. the platform
				// edge).
				return "5xx:" + this.status + (this.body.contains("\"request_id\"") ? " (app)" : " (edge)");
			}
			if (this.status < 300) {
				return (this.status == 201 && isReplay()) ? "201 (replay)" : String.valueOf(this.status);
			}
			try {
				JsonNode error = json().get("error");
				return this.status + ":" + ((error != null) ? error.asString() : "?");
			}
			catch (RuntimeException ex) {
				return this.status + ":?";
			}
		}

	}

}
