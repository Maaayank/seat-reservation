package com.paytm.seats.burst;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Reproduces the on-sale stampede against a running service and checks every
 * invariant from the problem statement.
 *
 * <pre>
 * java -jar burst.jar &lt;BASE_URL&gt; &lt;ADMIN_KEY&gt; [--profile smoke|full] [--max-in-flight N]
 * </pre>
 *
 * Scenarios, on one fresh show:
 * <ol>
 * <li>Setup: wait for readiness, create the show, mint tokens.</li>
 * <li>On-sale wave, all at once: hot-seat storm (many users, same seat), skewed
 * stampede, and idempotent retry groups (same key sent in parallel). An
 * invariant poller reads the show every 200 ms during the wave.</li>
 * <li>Same key, different seats -> 409.</li>
 * <li>Per-user limit: one user, 10 parallel reserves, limit 4.</li>
 * <li>Spoof: a body user_id is ignored; another user cannot cancel.</li>
 * <li>Cancel racing rebookers.</li>
 * <li>Final reconciliation against the API and the Prometheus metrics.</li>
 * </ol>
 * Exit code 0 only if every check passes.
 */
public final class Burst {

	private static final int LIMIT = 4;

	private final Api api;

	/** Separate client so the invariant poller never queues behind the wave. */
	private final Api pollerApi;

	private final Profile profile;

	private final Report report = new Report();

	private final Random random = new Random(42);

	/** Every reserve result of the run, for metric reconciliation. */
	private final List<Api.Result> allReserves = Collections.synchronizedList(new ArrayList<>());

	private final List<Api.Result> allCancels = Collections.synchronizedList(new ArrayList<>());

	/** Seat -> reservation id, as the client believes the final state should be. */
	private final Map<String, String> expectedConfirmed = new ConcurrentHashMap<>();

	/** Seats targeted by a request whose outcome the client never learned (timeout). */
	private final Set<String> uncertainSeats = ConcurrentHashMap.newKeySet();

	private String showId;

	/** Tokens not used by any scenario, for fallbacks. */
	private Map<String, String> spareTokens = Map.of();

	private Burst(Api api, Api pollerApi, Profile profile) {
		this.api = api;
		this.pollerApi = pollerApi;
		this.profile = profile;
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: burst <BASE_URL> <ADMIN_KEY> [--profile smoke|full] [--max-in-flight N]");
			System.exit(2);
		}
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
		Api api = new Api(args[0], args[1], inFlight, Duration.ofSeconds(60));
		System.out.printf(Locale.ROOT, "burst -> %s | profile %s | max in flight %d%n", args[0],
				profile.name().toLowerCase(Locale.ROOT), inFlight);
		Burst burst = new Burst(api, new Api(args[0], args[1], 2, Duration.ofSeconds(10)), profile);
		burst.run();
		System.exit(burst.report.failed() ? 1 : 0);
	}

	private void run() throws Exception {
		waitUntilReady();
		setUp();
		Map<String, String> tokens = mintTokens();
		Wave wave = onSaleWave(tokens);
		idempotencyReuse(wave);
		perUserLimit(tokens);
		spoof(tokens);
		cancelRacingRebookers(tokens, wave);
		finalReconciliation();
		this.report.print();
	}

	// ---- 1. setup -----------------------------------------------------------------

	private void waitUntilReady() throws InterruptedException {
		Instant start = Instant.now();
		Instant deadline = start.plus(Duration.ofMinutes(3));
		while (true) {
			Api.Result ready = this.api.get("/readyz");
			if (ready.status() == 200) {
				long ms = Duration.between(start, Instant.now()).toMillis();
				System.out.printf(Locale.ROOT, "  OK service ready (%d ms, includes any cold start)%n", ms);
				this.report.check("Service ready", Report.Verdict.PASS, ms + " ms to ready (cold start included)");
				return;
			}
			if (Instant.now().isAfter(deadline)) {
				this.report.check("Service ready", false, "not ready after 3 min: " + ready.outcome());
				this.report.print();
				System.exit(1);
			}
			Thread.sleep(2_000);
		}
	}

	private void setUp() {
		List<String> seats = Api.labels(this.profile.rows, this.profile.perRow);
		Api.Result created = this.api.admin("/shows", Map.of("name", "burst-" + Instant.now(), "seats", seats,
				"price_paise", 25_000, "per_user_limit", LIMIT));
		if (created.status() != 201) {
			throw new IllegalStateException("create show failed: " + created.status() + " " + created.body());
		}
		this.showId = created.json().get("id").asString();
		System.out.printf(Locale.ROOT, "  OK show %s with %d seats, limit %d%n", this.showId, seats.size(), LIMIT);
	}

	private Map<String, String> mintTokens() {
		int scenarioUsers = this.profile.waveUsers() + this.profile.limitUsers + 2
				+ this.profile.cancelSeats * this.profile.rebookers;
		int users = scenarioUsers + 5;
		String run = UUID.randomUUID().toString().substring(0, 8);
		List<String> ids = new ArrayList<>(users);
		for (int i = 0; i < users; i++) {
			ids.add("b" + run + "-" + i);
		}
		long start = System.nanoTime();
		Map<String, String> tokens = this.api.mintTokens(ids);
		this.spareTokens = tokens.entrySet()
			.stream()
			.skip(scenarioUsers)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
		System.out.printf(Locale.ROOT, "  OK minted %d tokens in %d ms%n", tokens.size(),
				(System.nanoTime() - start) / 1_000_000);
		return tokens;
	}

	// ---- 2. on-sale wave ------------------------------------------------------------

	private record Shot(String user, String token, String seat, String key, String kind, int group) {
	}

	private record Wave(List<Shot> shots, List<Api.Result> results, Map<Integer, String> wonGroups,
			Map<String, String> winnerTokens) {
	}

	private Wave onSaleWave(Map<String, String> tokens) throws Exception {
		Iterable<Map.Entry<String, String>> users = tokens.entrySet();
		var it = users.iterator();
		List<String> waveRows = new ArrayList<>();
		for (int r = 0; r < this.profile.rows - 1; r++) {
			waveRows.add(Api.rowName(r));
		}
		List<String> goodSeats = new ArrayList<>();
		List<String> otherSeats = new ArrayList<>();
		for (int r = 0; r < waveRows.size(); r++) {
			for (int c = 1; c <= this.profile.perRow; c++) {
				(r < 3 ? goodSeats : otherSeats).add(waveRows.get(r) + c);
			}
		}

		List<Shot> shots = new ArrayList<>();
		// Hot-seat storm: many users, same seat.
		for (int h = 1; h <= this.profile.hotSeats; h++) {
			for (int u = 0; u < this.profile.hotUsers; u++) {
				var user = it.next();
				shots.add(new Shot(user.getKey(), user.getValue(), "A" + h, UUID.randomUUID().toString(), "hot", -1));
			}
		}
		// Idempotent retry groups: one user sends the same request several times at once.
		for (int g = 0; g < this.profile.idemGroups; g++) {
			var user = it.next();
			String seat = goodSeats.get(this.random.nextInt(goodSeats.size()));
			String key = UUID.randomUUID().toString();
			for (int r = 0; r < this.profile.idemRetries; r++) {
				shots.add(new Shot(user.getKey(), user.getValue(), seat, key, "retry", g));
			}
		}
		// Stampede: 70% of users want the good rows.
		for (int s = 0; s < this.profile.stampede; s++) {
			var user = it.next();
			List<String> pool = (this.random.nextDouble() < 0.7 || otherSeats.isEmpty()) ? goodSeats : otherSeats;
			String seat = pool.get(this.random.nextInt(pool.size()));
			shots.add(new Shot(user.getKey(), user.getValue(), seat, UUID.randomUUID().toString(), "stampede", -1));
		}
		Collections.shuffle(shots, this.random);

		System.out.printf(Locale.ROOT, "  .. on-sale wave: %d requests (%d hot seats x %d users, %d retry groups x %d, %d stampede)%n",
				shots.size(), this.profile.hotSeats, this.profile.hotUsers, this.profile.idemGroups,
				this.profile.idemRetries, this.profile.stampede);

		Poller poller = new Poller();
		Thread pollerThread = Thread.ofVirtual().start(poller);
		List<Callable<Api.Result>> tasks = shots.stream()
			.<Callable<Api.Result>>map((s) -> () -> this.api.reserve(s.token(), this.showId, List.of(s.seat()), s.key(),
					Map.of()))
			.toList();
		long start = System.nanoTime();
		List<Api.Result> results = concurrently(tasks);
		double wallMs = (System.nanoTime() - start) / 1_000_000.0;
		poller.stop.set(true);
		pollerThread.join();
		this.allReserves.addAll(results);
		this.report.scenario("on-sale wave", results, wallMs);

		// Per-seat winners.
		Map<String, Set<String>> winners = new HashMap<>();
		Map<String, String> winnerTokens = new HashMap<>();
		Map<Integer, List<Api.Result>> groups = new HashMap<>();
		for (int i = 0; i < shots.size(); i++) {
			Shot shot = shots.get(i);
			Api.Result result = results.get(i);
			if (result.clientError() != null) {
				this.uncertainSeats.add(shot.seat());
			}
			if (result.status() == 201) {
				String rid = result.json().get("reservation_id").asString();
				winners.computeIfAbsent(shot.seat(), (k) -> new HashSet<>()).add(rid);
				this.expectedConfirmed.put(shot.seat(), rid);
				winnerTokens.put(shot.seat(), shot.token());
			}
			if (shot.group() >= 0) {
				groups.computeIfAbsent(shot.group(), (k) -> new ArrayList<>()).add(result);
			}
		}
		Set<String> targeted = shots.stream().map(Shot::seat).collect(Collectors.toSet());
		List<String> doubleSold = winners.entrySet()
			.stream()
			.filter((e) -> e.getValue().size() > 1)
			.map(Map.Entry::getKey)
			.toList();
		List<String> noWinner = targeted.stream()
			.filter((s) -> !winners.containsKey(s) && !this.uncertainSeats.contains(s))
			.toList();

		long fivexx = results.stream().filter((r) -> r.status() >= 500).count();
		this.report.check("Zero 5xx in on-sale wave", fivexx == 0, fivexx + " responses >= 500");
		this.report.check("No seat sold twice", doubleSold.isEmpty(),
				doubleSold.isEmpty() ? winners.size() + " seats, one winner each" : "double-sold: " + doubleSold);
		this.report.check("Every contested seat has exactly one winner", noWinner.isEmpty(),
				noWinner.isEmpty() ? targeted.size() + " targeted seats" : "no winner: " + noWinner);

		for (int h = 1; h <= this.profile.hotSeats; h++) {
			String seat = "A" + h;
			List<Api.Result> forSeat = new ArrayList<>();
			for (int i = 0; i < shots.size(); i++) {
				if (shots.get(i).seat().equals(seat)) {
					forSeat.add(results.get(i));
				}
			}
			Map<String, Long> dist = Report.countBy(forSeat, Api.Result::outcome);
			long created = dist.getOrDefault("201", 0L);
			// Requests without an HTTP response are reported separately (client-side); judge the rest.
			boolean clean = forSeat.stream()
				.filter((r) -> r.clientError() == null)
				.allMatch((r) -> r.status() == 201 || r.outcome().equals("409:seat_taken"));
			this.report.check("Hot seat " + seat + ": one 201, rest 409", created == 1 && clean, dist.toString());
		}

		Map<Integer, String> wonGroups = new HashMap<>();
		int badGroups = 0;
		for (Map.Entry<Integer, List<Api.Result>> group : groups.entrySet()) {
			List<Api.Result> rs = group.getValue();
			Set<String> rids = rs.stream()
				.filter((r) -> r.status() == 201)
				.map((r) -> r.json().get("reservation_id").asString())
				.collect(Collectors.toSet());
			long fresh = rs.stream().filter((r) -> r.status() == 201 && !r.isReplay()).count();
			boolean allWon = rs.stream().allMatch((r) -> r.status() == 201) && rids.size() == 1 && fresh == 1;
			boolean allLost = rs.stream().allMatch((r) -> r.outcome().equals("409:seat_taken"));
			if (allWon) {
				wonGroups.put(group.getKey(), rids.iterator().next());
			}
			else if (!allLost && rs.stream().noneMatch((r) -> r.clientError() != null)) {
				badGroups++;
			}
		}
		this.report.check("Same key retried in parallel -> one reservation", badGroups == 0,
				groups.size() + " groups: " + wonGroups.size() + " won once (rest replayed), "
						+ (groups.size() - wonGroups.size() - badGroups) + " lost cleanly"
						+ ((badGroups > 0) ? ", " + badGroups + " MIXED" : ""));
		this.report.check("Invariant during wave (poller every 200 ms)", poller.breaches.isEmpty() && poller.samples.get() > 0,
				poller.samples.get() + " samples, " + poller.breaches.size() + " breaches"
						+ (poller.breaches.isEmpty() ? "" : ": " + poller.breaches.subList(0, Math.min(3, poller.breaches.size()))));
		return new Wave(shots, results, wonGroups, winnerTokens);
	}

	/** Reads the show every 200 ms while the wave runs; any count mismatch is a breach. */
	private final class Poller implements Runnable {

		final AtomicBoolean stop = new AtomicBoolean();

		final AtomicInteger samples = new AtomicInteger();

		final List<String> breaches = Collections.synchronizedList(new ArrayList<>());

		@Override
		public void run() {
			while (!this.stop.get()) {
				Api.Result result = Burst.this.pollerApi.get("/shows/" + Burst.this.showId);
				if (result.status() == 200) {
					JsonNode c = result.json().get("counts");
					int sum = c.get("available").asInt() + c.get("held").asInt() + c.get("confirmed").asInt();
					this.samples.incrementAndGet();
					if (sum != c.get("total").asInt()) {
						this.breaches.add(Instant.now() + " sum=" + sum + " total=" + c.get("total").asInt());
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

	}

	// ---- 3. same key, different body --------------------------------------------------

	private void idempotencyReuse(Wave wave) {
		List<Api.Result> results = new ArrayList<>();
		String freeSeat = lastRowSeat(this.profile.perRow);
		int probes = 0;
		for (Map.Entry<Integer, String> won : wave.wonGroups().entrySet()) {
			if (probes++ == 5) {
				break;
			}
			Shot shot = wave.shots().stream().filter((s) -> s.group() == won.getKey()).findFirst().orElseThrow();
			results.add(this.api.reserve(shot.token(), this.showId, List.of(freeSeat), shot.key(), Map.of()));
		}
		if (results.isEmpty()) {
			// No retry group won its seat in the wave: set up a winning key first.
			Map.Entry<String, String> user = takeUsers(this.spareTokens, 0, 1).get(0);
			String key = UUID.randomUUID().toString();
			String seat = lastRowSeat(this.profile.perRow - 1);
			Api.Result won = this.api.reserve(user.getValue(), this.showId, List.of(seat), key, Map.of());
			this.allReserves.add(won);
			if (won.status() == 201) {
				this.expectedConfirmed.put(seat, won.json().get("reservation_id").asString());
				results.add(this.api.reserve(user.getValue(), this.showId, List.of(freeSeat), key, Map.of()));
			}
		}
		this.allReserves.addAll(results);
		this.report.scenario("same key, other seats", results, results.stream().mapToDouble(Api.Result::latencyMs).sum());
		boolean ok = !results.isEmpty() && results.stream().allMatch((r) -> r.outcome().equals("409:idempotency_key_reuse"));
		this.report.check("Same key + different seats -> 409", ok,
				results.isEmpty() ? "no group won a seat; nothing to probe" : Report.countBy(results, Api.Result::outcome).toString());
	}

	// ---- 4. per-user limit ------------------------------------------------------------

	private void perUserLimit(Map<String, String> tokens) throws Exception {
		List<Map.Entry<String, String>> users = takeUsers(tokens, this.profile.waveUsers(), this.profile.limitUsers);
		List<Callable<Api.Result>> tasks = new ArrayList<>();
		List<String> seatOf = new ArrayList<>();
		int seat = 1;
		for (Map.Entry<String, String> user : users) {
			for (int i = 0; i < 10; i++) {
				String label = lastRowSeat(seat++);
				seatOf.add(label);
				tasks.add(() -> this.api.reserve(user.getValue(), this.showId, List.of(label), UUID.randomUUID().toString(),
						Map.of()));
			}
		}
		long start = System.nanoTime();
		List<Api.Result> results = concurrently(tasks);
		this.report.scenario("per-user limit", results, (System.nanoTime() - start) / 1_000_000.0);
		this.allReserves.addAll(results);
		boolean ok = true;
		StringBuilder detail = new StringBuilder();
		for (int u = 0; u < users.size(); u++) {
			List<Api.Result> mine = results.subList(u * 10, u * 10 + 10);
			long won = mine.stream().filter((r) -> r.status() == 201).count();
			long limited = mine.stream().filter((r) -> r.outcome().equals("409:per_user_limit")).count();
			ok &= won == LIMIT && limited == 10 - LIMIT;
			detail.append("user").append(u + 1).append(": ").append(won).append(" ok, ").append(limited).append(" limited; ");
		}
		for (int i = 0; i < results.size(); i++) {
			if (results.get(i).status() == 201) {
				this.expectedConfirmed.put(seatOf.get(i), results.get(i).json().get("reservation_id").asString());
			}
		}
		this.report.check("Per-user limit: 10 parallel, limit 4 -> 4 held", ok, detail.toString().trim());
	}

	// ---- 5. spoofed identity ------------------------------------------------------------

	private void spoof(Map<String, String> tokens) {
		List<Map.Entry<String, String>> pair = takeUsers(tokens, this.profile.waveUsers() + this.profile.limitUsers, 2);
		Map.Entry<String, String> attacker = pair.get(0);
		Map.Entry<String, String> victim = pair.get(1);
		String seat = lastRowSeat(this.profile.limitUsers * 10 + 1);
		Api.Result reserved = this.api.reserve(attacker.getValue(), this.showId, List.of(seat), UUID.randomUUID().toString(),
				Map.of("user_id", victim.getKey()));
		this.allReserves.add(reserved);
		boolean actsAsToken = reserved.status() == 201
				&& reserved.json().get("user_id").asString().equals(attacker.getKey());
		this.report.check("Spoofed body user_id is ignored", actsAsToken,
				reserved.outcome() + ", user_id=" + ((reserved.status() == 201) ? reserved.json().get("user_id").asString() : "-"));
		if (reserved.status() != 201) {
			return;
		}
		String rid = reserved.json().get("reservation_id").asString();
		Api.Result stolen = this.api.cancel(victim.getValue(), rid);
		Api.Result own = this.api.cancel(attacker.getValue(), rid);
		this.allCancels.add(own);
		this.report.check("Only the owner can cancel", stolen.status() == 404 && own.status() == 200,
				"other user -> " + stolen.outcome() + ", owner -> " + own.outcome());
	}

	// ---- 6. cancel racing rebookers -----------------------------------------------------

	private void cancelRacingRebookers(Map<String, String> tokens, Wave wave) throws Exception {
		List<String> seats = wave.winnerTokens().keySet().stream().sorted().limit(this.profile.cancelSeats).toList();
		List<Map.Entry<String, String>> rebookers = takeUsers(tokens, this.profile.waveUsers() + this.profile.limitUsers + 2,
				seats.size() * this.profile.rebookers);
		List<Callable<Api.Result>> tasks = new ArrayList<>();
		List<String> taskSeat = new ArrayList<>();
		List<Boolean> isCancel = new ArrayList<>();
		int next = 0;
		for (String seat : seats) {
			String rid = this.expectedConfirmed.get(seat);
			String owner = wave.winnerTokens().get(seat);
			tasks.add(() -> this.api.cancel(owner, rid));
			taskSeat.add(seat);
			isCancel.add(true);
			for (int r = 0; r < this.profile.rebookers; r++) {
				String token = rebookers.get(next++).getValue();
				tasks.add(() -> this.api.reserve(token, this.showId, List.of(seat), UUID.randomUUID().toString(), Map.of()));
				taskSeat.add(seat);
				isCancel.add(false);
			}
		}
		long start = System.nanoTime();
		List<Api.Result> results = concurrently(tasks);
		this.report.scenario("cancel vs rebookers", results, (System.nanoTime() - start) / 1_000_000.0);

		Map<String, Integer> rebookWins = new HashMap<>();
		boolean cancelsOk = true;
		for (int i = 0; i < results.size(); i++) {
			Api.Result result = results.get(i);
			String seat = taskSeat.get(i);
			if (isCancel.get(i)) {
				this.allCancels.add(result);
				cancelsOk &= result.status() == 200;
				if (result.status() == 200) {
					this.expectedConfirmed.remove(seat, result.json().get("reservation_id").asString());
				}
			}
			else {
				this.allReserves.add(result);
				if (result.clientError() != null) {
					this.uncertainSeats.add(seat);
				}
				if (result.status() == 201) {
					rebookWins.merge(seat, 1, Integer::sum);
					this.expectedConfirmed.put(seat, result.json().get("reservation_id").asString());
				}
			}
		}
		long fivexx = results.stream().filter((r) -> r.status() >= 500).count();
		boolean atMostOne = rebookWins.values().stream().allMatch((n) -> n <= 1);
		this.report.check("Cancel racing rebookers: no double-sell, no 5xx", cancelsOk && atMostOne && fivexx == 0,
				seats.size() + " seats, " + rebookWins.size() + " rebooked, 5xx=" + fivexx);
	}

	// ---- 7. final reconciliation ---------------------------------------------------------

	private void finalReconciliation() throws InterruptedException {
		Api.Result show = this.api.get("/shows/" + this.showId);
		JsonNode body = show.json();
		JsonNode counts = body.get("counts");
		int available = counts.get("available").asInt();
		int held = counts.get("held").asInt();
		int confirmed = counts.get("confirmed").asInt();
		int total = counts.get("total").asInt();
		this.report.check("available + held + confirmed == total", available + held + confirmed == total,
				available + " + " + held + " + " + confirmed + " = " + (available + held + confirmed) + " (total " + total + ")");

		Set<String> apiConfirmed = new HashSet<>();
		body.get("seats").forEach((s) -> {
			if (s.get("status").asString().equals("confirmed")) {
				apiConfirmed.add(s.get("label").asString());
			}
		});
		Set<String> missing = new HashSet<>(this.expectedConfirmed.keySet());
		missing.removeAll(apiConfirmed);
		Set<String> unexpected = new HashSet<>(apiConfirmed);
		unexpected.removeAll(this.expectedConfirmed.keySet());
		unexpected.removeAll(this.uncertainSeats);
		this.report.check("Confirmed seats match what clients were told", missing.isEmpty() && unexpected.isEmpty(),
				apiConfirmed.size() + " confirmed; missing " + missing.size() + ", unexpected " + unexpected.size()
						+ (this.uncertainSeats.isEmpty() ? "" : " (" + this.uncertainSeats.size() + " seats had timeouts)"));

		long fivexx = this.allReserves.stream().filter((r) -> r.status() >= 500).count()
				+ this.allCancels.stream().filter((r) -> r.status() >= 500).count();
		long clientErrors = this.allReserves.stream().filter((r) -> r.clientError() != null).count();
		this.report.check("Zero 5xx across the whole run", fivexx == 0, fivexx + " responses >= 500");
		this.report.check("Client-side errors (timeouts, refused)", clientErrors == 0 ? Report.Verdict.PASS : Report.Verdict.WARN,
				clientErrors + " requests without an HTTP response (not server errors)");
		reconcileMetrics(apiConfirmed.size());
	}

	private void reconcileMetrics(int apiConfirmed) throws InterruptedException {
		long created = this.allReserves.stream().filter((r) -> r.status() == 201 && !r.isReplay()).count();
		long replays = this.allReserves.stream().filter(Api.Result::isReplay).count();
		long seatTaken = this.allReserves.stream().filter((r) -> r.outcome().equals("409:seat_taken")).count();
		long limited = this.allReserves.stream().filter((r) -> r.outcome().equals("409:per_user_limit")).count();
		long cancelled = this.allCancels.stream().filter((r) -> r.status() == 200).count();

		String metrics = null;
		Instant deadline = Instant.now().plusSeconds(10);
		while (Instant.now().isBefore(deadline)) {
			Api.Result scrape = this.api.get("/actuator/prometheus");
			if (scrape.status() != 200) {
				break;
			}
			metrics = scrape.body();
			if (metric(metrics, "seats", "state=\"confirmed\"") == apiConfirmed) {
				break;
			}
			Thread.sleep(250);
		}
		if (metrics == null) {
			this.report.check("Metrics reconcile with API", Report.Verdict.WARN, "/actuator/prometheus not reachable");
			return;
		}
		Map<String, long[]> pairs = new LinkedHashMap<>();
		pairs.put("reservations_confirmed_total", new long[] { metric(metrics, "reservations_confirmed_total", null), created });
		pairs.put("declined{seat_taken}", new long[] { metric(metrics, "reservations_declined_total", "reason=\"seat_taken\""), seatTaken });
		pairs.put("declined{per_user_limit}", new long[] { metric(metrics, "reservations_declined_total", "reason=\"per_user_limit\""), limited });
		pairs.put("declined{idempotent_replay}", new long[] { metric(metrics, "reservations_declined_total", "reason=\"idempotent_replay\""), replays });
		// The spoof scenario's cancel is not part of a counted race but is counted by the service.
		pairs.put("reservations_cancelled_total", new long[] { metric(metrics, "reservations_cancelled_total", null), cancelled });
		pairs.put("seats{confirmed} gauge", new long[] { metric(metrics, "seats", "state=\"confirmed\""), apiConfirmed });
		boolean uncertain = !this.uncertainSeats.isEmpty();
		pairs.forEach((name, v) -> {
			boolean ok = v[0] == v[1];
			this.report.check("Metric " + name, ok ? Report.Verdict.PASS : (uncertain ? Report.Verdict.WARN : Report.Verdict.FAIL),
					"metric " + v[0] + " vs observed " + v[1]);
		});
	}

	private long metric(String metrics, String name, String labelFilter) {
		Pattern p = Pattern.compile("^" + Pattern.quote(name) + "\\{([^}]*)} ([0-9.eE+-]+)$", Pattern.MULTILINE);
		Matcher m = p.matcher(metrics);
		double sum = 0;
		boolean found = false;
		while (m.find()) {
			String labels = m.group(1);
			if (labels.contains("show_id=\"" + this.showId + "\"") && (labelFilter == null || labels.contains(labelFilter))) {
				sum += Double.parseDouble(m.group(2));
				found = true;
			}
		}
		return found ? (long) sum : -1;
	}

	// ---- helpers ----------------------------------------------------------------------

	private String lastRowSeat(int seat) {
		return Api.rowName(this.profile.rows - 1) + seat;
	}

	private static List<Map.Entry<String, String>> takeUsers(Map<String, String> tokens, int skip, int count) {
		return tokens.entrySet().stream().skip(skip).limit(count).toList();
	}

	/** Starts every task at the same instant on virtual threads; results in task order. */
	private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<T>> futures = new ArrayList<>(tasks.size());
			for (Callable<T> task : tasks) {
				futures.add(executor.submit(() -> {
					gate.await();
					return task.call();
				}));
			}
			gate.countDown();
			List<T> results = new ArrayList<>(tasks.size());
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

	/** Load shapes. smoke: safe for a free-tier demo. full: ~20k requests in the wave. */
	enum Profile {

		SMOKE(5, 50, 3, 100, 20, 5, 1_100, 2, 5, 10, 300),

		FULL(21, 50, 10, 500, 200, 5, 14_000, 4, 10, 20, 2_000);

		final int rows;

		final int perRow;

		final int hotSeats;

		final int hotUsers;

		final int idemGroups;

		final int idemRetries;

		final int stampede;

		final int limitUsers;

		final int cancelSeats;

		final int rebookers;

		final int maxInFlight;

		Profile(int rows, int perRow, int hotSeats, int hotUsers, int idemGroups, int idemRetries, int stampede,
				int limitUsers, int cancelSeats, int rebookers, int maxInFlight) {
			this.rows = rows;
			this.perRow = perRow;
			this.hotSeats = hotSeats;
			this.hotUsers = hotUsers;
			this.idemGroups = idemGroups;
			this.idemRetries = idemRetries;
			this.stampede = stampede;
			this.limitUsers = limitUsers;
			this.cancelSeats = cancelSeats;
			this.rebookers = rebookers;
			this.maxInFlight = maxInFlight;
		}

		int waveUsers() {
			return this.hotSeats * this.hotUsers + this.idemGroups + this.stampede;
		}

	}

}
