package com.paytm.seats.burst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * The on-sale moment: every request released at once on a fresh show.
 *
 * <ul>
 * <li>Hot-seat storm: {@code hotUsers} different users want each of seats A1..An.</li>
 * <li>Retry groups: one user sends the same request (same key) several times in
 * parallel.</li>
 * <li>Stampede: single-seat requests, 70% aimed at the good rows A-C.</li>
 * </ul>
 *
 * Every request is for one seat and every user is fresh, so the expected result is exact:
 * each targeted seat has exactly one winner, everyone else gets 409.
 */
final class OnSaleWave {

	private final Run run;

	private final Api pollerApi;

	OnSaleWave(Run run, Api pollerApi) {
		this.run = run;
		this.pollerApi = pollerApi;
	}

	/** One request in the wave. {@code group} is the retry group, or -1. */
	record Shot(Run.User user, String seat, String key, int group) {
	}

	/**
	 * What later scenarios need: the winners per seat, and the retry groups that won (for
	 * the key-reuse probe).
	 */
	record Result(Map<String, Run.User> winners, Map<Integer, Shot> wonGroups) {
	}

	Result fire() throws Exception {
		List<Shot> shots = plan();
		System.out.printf(Locale.ROOT,
				"  .. on-sale wave: %d requests (%d hot seats x %d users, %d retry groups x %d, %d stampede)%n",
				shots.size(), this.run.profile.hotSeats, this.run.profile.hotUsers, this.run.profile.retryGroups,
				this.run.profile.retries, this.run.profile.stampede);

		InvariantPoller poller = new InvariantPoller(this.pollerApi, this.run.showId);
		Thread pollerThread = Thread.ofVirtual().start(poller);
		List<Callable<Api.Result>> tasks = new ArrayList<>(shots.size());
		for (Shot shot : shots) {
			tasks.add(() -> this.run.api.reserve(shot.user().token(), this.run.showId, List.of(shot.seat()), shot.key(),
					Map.of()));
		}
		long start = System.nanoTime();
		List<Api.Result> results = Run.concurrently(tasks);
		double wallMs = Run.millisSince(start);
		poller.stop();
		pollerThread.join();

		this.run.reserves.addAll(results);
		this.run.report.scenario("on-sale wave", results, wallMs);
		Result result = check(shots, results);
		poller.report(this.run.report);
		return result;
	}

	private List<Shot> plan() {
		Profile p = this.run.profile;
		List<String> goodSeats = new ArrayList<>();
		List<String> otherSeats = new ArrayList<>();
		for (int r = 0; r < p.rows - 1; r++) {
			for (int c = 1; c <= p.perRow; c++) {
				(r < 3 ? goodSeats : otherSeats).add(Profile.rowName(r) + c);
			}
		}

		List<Shot> shots = new ArrayList<>();
		for (int h = 1; h <= p.hotSeats; h++) {
			for (Run.User user : this.run.takeUsers(p.hotUsers)) {
				shots.add(new Shot(user, "A" + h, newKey(), -1));
			}
		}
		for (int g = 0; g < p.retryGroups; g++) {
			Run.User user = this.run.takeUser();
			String seat = pick(goodSeats);
			String key = newKey();
			for (int r = 0; r < p.retries; r++) {
				shots.add(new Shot(user, seat, key, g));
			}
		}
		for (Run.User user : this.run.takeUsers(p.stampede)) {
			boolean wantsGoodRow = this.run.random.nextDouble() < 0.7 || otherSeats.isEmpty();
			shots.add(new Shot(user, pick(wantsGoodRow ? goodSeats : otherSeats), newKey(), -1));
		}
		Collections.shuffle(shots, this.run.random);
		return shots;
	}

	private Result check(List<Shot> shots, List<Api.Result> results) {
		Map<String, Set<String>> reservationsBySeat = new HashMap<>();
		Map<String, Run.User> winners = new HashMap<>();
		Map<Integer, List<Integer>> groups = new HashMap<>();
		for (int i = 0; i < shots.size(); i++) {
			Shot shot = shots.get(i);
			Api.Result result = results.get(i);
			if (result.clientError() != null) {
				this.run.uncertainSeats.add(shot.seat());
			}
			if (result.status() == 201) {
				String reservationId = result.reservationId();
				reservationsBySeat.computeIfAbsent(shot.seat(), (k) -> new HashSet<>()).add(reservationId);
				this.run.expectedConfirmed.put(shot.seat(), reservationId);
				winners.put(shot.seat(), shot.user());
			}
			if (shot.group() >= 0) {
				groups.computeIfAbsent(shot.group(), (k) -> new ArrayList<>()).add(i);
			}
		}

		Report report = this.run.report;
		long serverErrors = results.stream().filter((r) -> r.status() >= 500).count();
		report.check("Zero 5xx in on-sale wave", serverErrors == 0, serverErrors + " responses >= 500");

		List<String> doubleSold = reservationsBySeat.entrySet()
			.stream()
			.filter((e) -> e.getValue().size() > 1)
			.map(Map.Entry::getKey)
			.toList();
		report.check("No seat sold twice", doubleSold.isEmpty(), doubleSold.isEmpty()
				? reservationsBySeat.size() + " seats, one winner each" : "double-sold: " + doubleSold);

		Set<String> targeted = shots.stream().map(Shot::seat).collect(Collectors.toSet());
		List<String> noWinner = targeted.stream()
			.filter((s) -> !winners.containsKey(s) && !this.run.uncertainSeats.contains(s))
			.toList();
		report.check("Every contested seat has exactly one winner", noWinner.isEmpty(),
				noWinner.isEmpty() ? targeted.size() + " targeted seats" : "no winner: " + noWinner);

		checkHotSeats(shots, results);
		Map<Integer, Shot> wonGroups = checkRetryGroups(shots, results, groups);
		return new Result(winners, wonGroups);
	}

	/**
	 * Each hot seat: exactly one 201; every answer that came back is 201 or seat_taken.
	 */
	private void checkHotSeats(List<Shot> shots, List<Api.Result> results) {
		for (int h = 1; h <= this.run.profile.hotSeats; h++) {
			String seat = "A" + h;
			List<Api.Result> forSeat = new ArrayList<>();
			for (int i = 0; i < shots.size(); i++) {
				if (shots.get(i).seat().equals(seat)) {
					forSeat.add(results.get(i));
				}
			}
			Map<String, Long> outcomes = Report.countBy(forSeat, Api.Result::outcome);
			boolean clean = forSeat.stream()
				.filter((r) -> r.clientError() == null)
				.allMatch((r) -> r.status() == 201 || r.outcome().equals("409:seat_taken"));
			this.run.report.check("Hot seat " + seat + ": one 201, rest 409",
					outcomes.getOrDefault("201", 0L) == 1 && clean, outcomes.toString());
		}
	}

	/**
	 * A retry group must be uniform: all 201 with one reservation (one fresh, rest
	 * replays), or all 409. A mix means a key reserved twice.
	 */
	private Map<Integer, Shot> checkRetryGroups(List<Shot> shots, List<Api.Result> results,
			Map<Integer, List<Integer>> groups) {
		Map<Integer, Shot> won = new HashMap<>();
		int mixed = 0;
		for (Map.Entry<Integer, List<Integer>> group : groups.entrySet()) {
			List<Api.Result> rs = group.getValue().stream().map(results::get).toList();
			Set<String> reservationIds = rs.stream()
				.filter((r) -> r.status() == 201)
				.map(Api.Result::reservationId)
				.collect(Collectors.toSet());
			long fresh = rs.stream().filter((r) -> r.status() == 201 && !r.isReplay()).count();
			boolean allWon = rs.stream().allMatch((r) -> r.status() == 201) && reservationIds.size() == 1 && fresh == 1;
			boolean allLost = rs.stream().allMatch((r) -> r.outcome().equals("409:seat_taken"));
			boolean incomplete = rs.stream().anyMatch((r) -> r.clientError() != null);
			if (allWon) {
				won.put(group.getKey(), shots.get(group.getValue().get(0)));
			}
			else if (!allLost && !incomplete) {
				mixed++;
			}
		}
		int lost = groups.size() - won.size() - mixed;
		this.run.report.check("Same key retried in parallel -> one reservation", mixed == 0,
				groups.size() + " groups: " + won.size() + " won once (rest replayed), " + lost + " lost cleanly"
						+ ((mixed > 0) ? ", " + mixed + " MIXED" : ""));
		return won;
	}

	private String pick(List<String> seats) {
		return seats.get(this.run.random.nextInt(seats.size()));
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
