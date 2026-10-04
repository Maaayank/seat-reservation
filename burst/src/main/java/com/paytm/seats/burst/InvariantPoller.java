package com.paytm.seats.burst;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;

/**
 * Reads the show every 200 ms while the wave runs and records any moment where available
 * + held + confirmed != total. Uses its own client so it never queues behind the wave's
 * requests.
 */
final class InvariantPoller implements Runnable {

	private final Api api;

	private final String showId;

	private final AtomicBoolean stop = new AtomicBoolean();

	private final AtomicInteger samples = new AtomicInteger();

	private final List<String> breaches = Collections.synchronizedList(new ArrayList<>());

	InvariantPoller(Api api, String showId) {
		this.api = api;
		this.showId = showId;
	}

	@Override
	public void run() {
		while (!this.stop.get()) {
			Api.Result result = this.api.get("/shows/" + this.showId);
			if (result.status() == 200) {
				JsonNode counts = result.json().get("counts");
				int sum = counts.get("available").asInt() + counts.get("held").asInt()
						+ counts.get("confirmed").asInt();
				int total = counts.get("total").asInt();
				this.samples.incrementAndGet();
				if (sum != total) {
					this.breaches.add(Instant.now() + " sum=" + sum + " total=" + total);
				}
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	void stop() {
		this.stop.set(true);
	}

	void report(Report report) {
		String detail = this.samples.get() + " samples, " + this.breaches.size() + " breaches";
		if (!this.breaches.isEmpty()) {
			detail += ": " + this.breaches.subList(0, Math.min(3, this.breaches.size()));
		}
		report.check("Invariant during wave (poller every 200 ms)", this.breaches.isEmpty() && this.samples.get() > 0,
				detail);
	}

}
