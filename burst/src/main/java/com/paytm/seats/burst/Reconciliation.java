package com.paytm.seats.burst;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * End-of-run checks: the server's final state against the client's ledger, and the
 * Prometheus counters against what clients actually saw.
 */
final class Reconciliation {

	private final Run run;

	Reconciliation(Run run) {
		this.run = run;
	}

	void verify() throws InterruptedException {
		Set<String> apiConfirmed = verifyShowState();
		verifyNoServerErrors();
		verifyMetrics(apiConfirmed.size());
	}

	/**
	 * Counts add up, and the confirmed seats are exactly the ones clients were told they
	 * won.
	 */
	private Set<String> verifyShowState() {
		Api.Result result = this.run.api.get("/shows/" + this.run.showId);
		if (result.status() != 200) {
			// The app died or is unreachable: nothing to reconcile against.
			this.run.report.check("Final show state readable", false,
					"GET /shows/{id} -> " + result.outcome() + " (app down or unreachable)");
			return Set.of();
		}
		JsonNode show = result.json();
		JsonNode counts = show.get("counts");
		int available = counts.get("available").asInt();
		int held = counts.get("held").asInt();
		int confirmed = counts.get("confirmed").asInt();
		int total = counts.get("total").asInt();
		this.run.report.check("available + held + confirmed == total", available + held + confirmed == total, available
				+ " + " + held + " + " + confirmed + " = " + (available + held + confirmed) + " (total " + total + ")");

		Set<String> apiConfirmed = new HashSet<>();
		show.get("seats").forEach((s) -> {
			if (s.get("status").asString().equals("confirmed")) {
				apiConfirmed.add(s.get("label").asString());
			}
		});
		Set<String> missing = new HashSet<>(this.run.expectedConfirmed.keySet());
		missing.removeAll(apiConfirmed);
		// A seat targeted by a request with an unknown outcome may legitimately be
		// confirmed.
		Set<String> unexpected = new HashSet<>(apiConfirmed);
		unexpected.removeAll(this.run.expectedConfirmed.keySet());
		unexpected.removeAll(this.run.uncertainSeats);
		String uncertainNote = this.run.uncertainSeats.isEmpty() ? ""
				: " (" + this.run.uncertainSeats.size() + " seats had requests without a response)";
		this.run.report.check("Confirmed seats match what clients were told", missing.isEmpty() && unexpected.isEmpty(),
				apiConfirmed.size() + " confirmed; missing " + missing.size() + ", unexpected " + unexpected.size()
						+ uncertainNote);
		return apiConfirmed;
	}

	private void verifyNoServerErrors() {
		long serverErrors = this.run.reserves.stream().filter((r) -> r.status() >= 500).count()
				+ this.run.cancels.stream().filter((r) -> r.status() >= 500).count();
		long clientErrors = this.run.reserves.stream().filter((r) -> r.clientError() != null).count();
		this.run.report.check("Zero 5xx across the whole run", serverErrors == 0, serverErrors + " responses >= 500");
		// A few unanswered requests are a warning. If many never got an answer, too
		// little
		// was actually tested to call the run a pass.
		long total = this.run.reserves.size();
		Report.Verdict verdict = (clientErrors == 0) ? Report.Verdict.PASS
				: (clientErrors * 10 > total) ? Report.Verdict.FAIL : Report.Verdict.WARN;
		this.run.report.check("Client-side errors (timeouts, refused)", verdict, clientErrors + " of " + total
				+ " requests without an HTTP response (not server errors; FAIL above 10%)");
	}

	/**
	 * Each counter must equal the matching client-side count. The seats gauge refreshes
	 * up to once per second after a write, so wait briefly for it to settle.
	 */
	private void verifyMetrics(int apiConfirmed) throws InterruptedException {
		String metrics = scrapeWhenSettled(apiConfirmed);
		if (metrics == null) {
			this.run.report.check("Metrics reconcile with API", Report.Verdict.WARN,
					"/actuator/prometheus not reachable");
			return;
		}
		Map<String, long[]> pairs = new LinkedHashMap<>();
		pairs.put("reservations_confirmed_total", new long[] { metric(metrics, "reservations_confirmed_total", null),
				count((r) -> r.status() == 201 && !r.isReplay()) });
		pairs.put("declined{seat_taken}", new long[] {
				metric(metrics, "reservations_declined_total", "reason=\"seat_taken\""), outcome("409:seat_taken") });
		pairs.put("declined{per_user_limit}",
				new long[] { metric(metrics, "reservations_declined_total", "reason=\"per_user_limit\""),
						outcome("409:per_user_limit") });
		pairs.put("declined{idempotent_replay}",
				new long[] { metric(metrics, "reservations_declined_total", "reason=\"idempotent_replay\""),
						count(Api.Result::isReplay) });
		pairs.put("reservations_cancelled_total", new long[] { metric(metrics, "reservations_cancelled_total", null),
				this.run.cancels.stream().filter((r) -> r.status() == 200).count() });
		pairs.put("seats{confirmed} gauge",
				new long[] { metric(metrics, "seats", "state=\"confirmed\""), apiConfirmed });

		// With unknown-outcome requests the server may have counted something the client
		// never saw.
		Report.Verdict onMismatch = this.run.uncertainSeats.isEmpty() ? Report.Verdict.FAIL : Report.Verdict.WARN;
		pairs.forEach((name, v) -> this.run.report.check("Metric " + name,
				(v[0] == v[1]) ? Report.Verdict.PASS : onMismatch, "metric " + v[0] + " vs observed " + v[1]));
	}

	private String scrapeWhenSettled(int apiConfirmed) throws InterruptedException {
		String metrics = null;
		Instant deadline = Instant.now().plusSeconds(10);
		while (Instant.now().isBefore(deadline)) {
			Api.Result scrape = this.run.api.get("/actuator/prometheus");
			if (scrape.status() != 200) {
				return metrics;
			}
			metrics = scrape.body();
			if (metric(metrics, "seats", "state=\"confirmed\"") == apiConfirmed) {
				return metrics;
			}
			Thread.sleep(250);
		}
		return metrics;
	}

	private long count(Predicate<Api.Result> filter) {
		return this.run.reserves.stream().filter(filter).count();
	}

	private long outcome(String outcome) {
		return count((r) -> r.outcome().equals(outcome));
	}

	/**
	 * Sum of a metric's series for this show (optionally filtered by a label); -1 if
	 * absent.
	 */
	private long metric(String metrics, String name, String labelFilter) {
		Pattern pattern = Pattern.compile("^" + Pattern.quote(name) + "\\{([^}]*)} ([0-9.eE+-]+)$", Pattern.MULTILINE);
		Matcher m = pattern.matcher(metrics);
		double sum = 0;
		boolean found = false;
		while (m.find()) {
			String labels = m.group(1);
			if (labels.contains("show_id=\"" + this.run.showId + "\"")
					&& (labelFilter == null || labels.contains(labelFilter))) {
				sum += Double.parseDouble(m.group(2));
				found = true;
			}
		}
		return found ? (long) sum : 0;
	}

}
