package com.paytm.seats.burst;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reproduces the on-sale stampede against a running service and checks every invariant
 * from the problem statement.
 *
 * <pre>
 * java -jar burst.jar &lt;BASE_URL&gt; &lt;ADMIN_KEY&gt; [--profile smoke|full] [--max-in-flight N]
 * </pre>
 *
 * Run order, on one fresh show:
 * <ol>
 * <li>Wait for readiness (reports cold-start time), create the show, mint tokens.</li>
 * <li>{@link OnSaleWave}: hot-seat storm + stampede + parallel retries, all at once, with
 * an {@link InvariantPoller}.</li>
 * <li>{@link FollowUpScenarios}: key reuse, per-user limit, spoofed identity, cancel
 * racing rebookers.</li>
 * <li>{@link Reconciliation}: final state and metrics against the client's ledger.</li>
 * </ol>
 * Exit code 0 only if every check passes.
 */
public final class Burst {

	static final int PER_USER_LIMIT = 4;

	private Burst() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: burst <BASE_URL> <ADMIN_KEY> [--profile smoke|full] [--max-in-flight N]");
			System.exit(2);
		}
		String baseUrl = args[0];
		String adminKey = args[1];
		Profile profile = Profile.SMOKE;
		Integer maxInFlight = null;
		for (int i = 2; i < args.length; i++) {
			switch (args[i]) {
				case "--profile" -> profile = Profile.valueOf(args[++i].toUpperCase(Locale.ROOT));
				case "--max-in-flight" -> maxInFlight = Integer.parseInt(args[++i]);
				default -> throw new IllegalArgumentException("unknown option " + args[i]);
			}
		}
		int inFlight = (maxInFlight != null) ? maxInFlight : profile.maxInFlight;
		System.out.printf(Locale.ROOT, "burst -> %s | profile %s | max in flight %d%n", baseUrl,
				profile.name().toLowerCase(Locale.ROOT), inFlight);

		Api api = new Api(baseUrl, adminKey, inFlight, Duration.ofSeconds(60));
		Api pollerApi = new Api(baseUrl, adminKey, 2, Duration.ofSeconds(10));
		Report report = run(api, pollerApi, profile);
		System.exit(report.failed() ? 1 : 0);
	}

	private static Report run(Api api, Api pollerApi, Profile profile) throws Exception {
		long readyMs = waitUntilReady(api);
		String showId = createShow(api, profile);
		Run run = new Run(api, profile, showId, mintTokens(api, profile));
		run.report.check("Service ready", Report.Verdict.PASS, readyMs + " ms to ready (cold start included)");

		OnSaleWave.Result wave = new OnSaleWave(run, pollerApi).fire();
		FollowUpScenarios followUps = new FollowUpScenarios(run);
		followUps.keyReuse(wave);
		followUps.perUserLimit();
		followUps.spoofedIdentity();
		followUps.cancelRacingRebookers(wave);
		new Reconciliation(run).verify();

		run.report.print();
		return run.report;
	}

	/**
	 * Polls /readyz for up to 3 minutes; a sleeping free-tier service needs about one.
	 */
	private static long waitUntilReady(Api api) throws InterruptedException {
		Instant start = Instant.now();
		Instant deadline = start.plus(Duration.ofMinutes(3));
		while (true) {
			Api.Result ready = api.get("/readyz");
			if (ready.status() == 200) {
				long ms = Duration.between(start, Instant.now()).toMillis();
				System.out.printf(Locale.ROOT, "  OK service ready (%d ms, includes any cold start)%n", ms);
				return ms;
			}
			if (Instant.now().isAfter(deadline)) {
				System.err.println("service not ready after 3 min: " + ready.outcome());
				System.exit(1);
			}
			Thread.sleep(2_000);
		}
	}

	private static String createShow(Api api, Profile profile) {
		List<String> seats = profile.allSeats();
		Api.Result created = api.admin("/shows", Map.of("name", "burst-" + Instant.now(), "seats", seats, "price_paise",
				25_000, "per_user_limit", PER_USER_LIMIT));
		if (created.status() != 201) {
			throw new IllegalStateException("create show failed: " + created.status() + " " + created.body());
		}
		String showId = created.json().get("id").asString();
		System.out.printf(Locale.ROOT, "  OK show %s with %d seats, limit %d%n", showId, seats.size(), PER_USER_LIMIT);
		return showId;
	}

	private static Map<String, String> mintTokens(Api api, Profile profile) {
		String runId = UUID.randomUUID().toString().substring(0, 8);
		List<String> ids = new ArrayList<>(profile.usersNeeded());
		for (int i = 0; i < profile.usersNeeded(); i++) {
			ids.add("b" + runId + "-" + i);
		}
		long start = System.nanoTime();
		Map<String, String> tokens = api.mintTokens(ids);
		System.out.printf(Locale.ROOT, "  OK minted %d tokens in %.0f ms%n", tokens.size(), Run.millisSince(start));
		return tokens;
	}

}
