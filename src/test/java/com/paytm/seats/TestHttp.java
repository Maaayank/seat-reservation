package com.paytm.seats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON HTTP client for integration tests. */
public final class TestHttp {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpClient client = HttpClient.newHttpClient();

	private final String baseUrl;

	public TestHttp(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	public Response get(String path, Map<String, String> headers) {
		return send(HttpRequest.newBuilder(URI.create(this.baseUrl + path)).GET(), headers);
	}

	public Response post(String path, String jsonBody, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
		return send(builder, headers);
	}

	private Response send(HttpRequest.Builder builder, Map<String, String> headers) {
		headers.forEach(builder::header);
		HttpRequest request = builder.build();
		try {
			for (int attempt = 1;; attempt++) {
				try {
					HttpResponse<String> response = this.client.send(request, HttpResponse.BodyHandlers.ofString());
					return new Response(response.statusCode(), response.body(), response);
				}
				catch (ConnectException ex) {
					// The connection was refused, so the server never saw the request:
					// safe to retry. Happens on Windows when hundreds of sockets open at
					// once.
					if (attempt == 5) {
						throw ex;
					}
					Thread.sleep(20L * attempt);
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	public record Response(int status, String body, HttpResponse<String> raw) {

		public JsonNode json() {
			return JSON.readTree(this.body);
		}

	}

}
