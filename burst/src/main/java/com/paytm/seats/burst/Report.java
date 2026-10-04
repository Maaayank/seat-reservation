package com.paytm.seats.burst;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Collects scenario stats and invariant checks; prints the final report. */
final class Report {

	enum Verdict {

		PASS, WARN, FAIL

	}

	private final List<String> scenarios = new ArrayList<>();

	private final List<String[]> checks = new ArrayList<>();

	private boolean failed;

	void scenario(String name, List<Api.Result> results, double wallMs) {
		Map<String, Long> outcomes = results.stream()
			.collect(Collectors.groupingBy(Api.Result::outcome, TreeMap::new, Collectors.counting()));
		double[] latencies = results.stream().mapToDouble(Api.Result::latencyMs).sorted().toArray();
		StringBuilder line = new StringBuilder();
		line.append(String.format(Locale.ROOT, "%n  %-22s %6d requests in %7.0f ms (%.0f req/s)", name, results.size(),
				wallMs, results.size() / Math.max(wallMs / 1000.0, 0.001)));
		line.append(
				String.format(Locale.ROOT, "%n  %-22s latency p50 %.0f ms | p95 %.0f ms | p99 %.0f ms | max %.0f ms",
						"", pct(latencies, 0.50), pct(latencies, 0.95), pct(latencies, 0.99), pct(latencies, 1.0)));
		outcomes.forEach((outcome, count) -> line
			.append(String.format(Locale.ROOT, "%n  %-22s   %-30s %6d", "", outcome, count)));
		this.scenarios.add(line.toString());
		System.out.printf(Locale.ROOT, "  OK %s: %d requests, %s%n", name, results.size(), outcomes);
	}

	void check(String name, Verdict verdict, String detail) {
		this.checks.add(new String[] { verdict.name(), name, detail });
		if (verdict == Verdict.FAIL) {
			this.failed = true;
		}
	}

	void check(String name, boolean ok, String detail) {
		check(name, ok ? Verdict.PASS : Verdict.FAIL, detail);
	}

	boolean failed() {
		return this.failed;
	}

	void print() {
		System.out.println();
		System.out.println("============================ OUTCOME DISTRIBUTION ============================");
		this.scenarios.forEach(System.out::println);
		System.out.println();
		System.out.println("============================ INVARIANT CHECKS =================================");
		for (String[] check : this.checks) {
			System.out.printf(Locale.ROOT, "  [%s] %-46s %s%n", check[0], check[1], check[2]);
		}
		System.out.println();
		System.out.println(this.failed ? "RESULT: FAIL" : "RESULT: PASS");
	}

	static <T> Map<String, Long> countBy(List<T> items, Function<T, String> key) {
		return items.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
	}

	private static double pct(double[] sorted, double p) {
		if (sorted.length == 0) {
			return 0;
		}
		int index = (int) Math.ceil(p * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

}
