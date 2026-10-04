package com.paytm.seats.burst;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Targeted checks after the wave. Each one uses fresh users and, except the cancel race,
 * seats of the reserved last row, so its expected outcome is exact.
 */
final class FollowUpScenarios {

	private static final int LIMIT = Burst.PER_USER_LIMIT;

	private final Run run;

	FollowUpScenarios(Run run) {
		this.run = run;
	}

	/** Same idempotency key, different seats → 409 idempotency_key_reuse. */
	void keyReuse(OnSaleWave.Result wave) {
		String otherSeat = this.run.profile.reservedSeat(this.run.profile.perRow);
		List<Api.Result> results = new ArrayList<>();
		for (OnSaleWave.Shot won : wave.wonGroups().values().stream().limit(5).toList()) {
			results.add(reserve(won.user(), otherSeat, won.key()));
		}
		if (results.isEmpty()) {
			// No retry group won its seat in the wave: create a winning key first.
			Run.User user = this.run.takeUser();
			String key = newKey();
			String seat = this.run.profile.reservedSeat(this.run.profile.perRow - 1);
			Api.Result won = reserve(user, seat, key);
			this.run.reserves.add(won);
			if (won.status() == 201) {
				this.run.expectedConfirmed.put(seat, won.reservationId());
				results.add(reserve(user, otherSeat, key));
			}
		}
		this.run.reserves.addAll(results);
		this.run.report.scenario("same key, other seats", results,
				results.stream().mapToDouble(Api.Result::latencyMs).sum());
		boolean ok = !results.isEmpty()
				&& results.stream().allMatch((r) -> r.outcome().equals("409:idempotency_key_reuse"));
		this.run.report.check("Same key + different seats -> 409", ok,
				Report.countBy(results, Api.Result::outcome).toString());
	}

	/**
	 * Each user fires 10 parallel single-seat reserves on a limit-4 show: exactly 4
	 * succeed.
	 */
	void perUserLimit() throws Exception {
		List<Run.User> users = this.run.takeUsers(this.run.profile.limitUsers);
		List<Callable<Api.Result>> tasks = new ArrayList<>();
		List<String> seatOfTask = new ArrayList<>();
		int nextSeat = 1;
		for (Run.User user : users) {
			for (int i = 0; i < 10; i++) {
				String seat = this.run.profile.reservedSeat(nextSeat++);
				seatOfTask.add(seat);
				tasks.add(() -> reserve(user, seat, newKey()));
			}
		}
		long start = System.nanoTime();
		List<Api.Result> results = Run.concurrently(tasks);
		this.run.report.scenario("per-user limit", results, Run.millisSince(start));
		this.run.reserves.addAll(results);

		boolean ok = true;
		StringBuilder detail = new StringBuilder();
		for (int u = 0; u < users.size(); u++) {
			List<Api.Result> mine = results.subList(u * 10, u * 10 + 10);
			long won = mine.stream().filter((r) -> r.status() == 201).count();
			long limited = mine.stream().filter((r) -> r.outcome().equals("409:per_user_limit")).count();
			ok &= won == LIMIT && limited == 10 - LIMIT;
			detail.append("user")
				.append(u + 1)
				.append(": ")
				.append(won)
				.append(" ok, ")
				.append(limited)
				.append(" limited; ");
		}
		for (int i = 0; i < results.size(); i++) {
			if (results.get(i).status() == 201) {
				this.run.expectedConfirmed.put(seatOfTask.get(i), results.get(i).reservationId());
			}
		}
		this.run.report.check("Per-user limit: 10 parallel, limit 4 -> 4 held", ok, detail.toString().trim());
	}

	/**
	 * An attacker puts the victim's id in the body: the reservation is still the
	 * attacker's. The victim cannot cancel it; the attacker can.
	 */
	void spoofedIdentity() {
		Run.User attacker = this.run.takeUser();
		Run.User victim = this.run.takeUser();
		String seat = this.run.profile.reservedSeat(this.run.profile.limitUsers * 10 + 1);
		Api.Result reserved = this.run.api.reserve(attacker.token(), this.run.showId, List.of(seat), newKey(),
				Map.of("user_id", victim.id()));
		this.run.reserves.add(reserved);
		String owner = (reserved.status() == 201) ? reserved.json().get("user_id").asString() : "-";
		this.run.report.check("Spoofed body user_id is ignored", owner.equals(attacker.id()),
				reserved.outcome() + ", user_id=" + owner);
		if (reserved.status() != 201) {
			return;
		}
		Api.Result byVictim = this.run.api.cancel(victim.token(), reserved.reservationId());
		Api.Result byOwner = this.run.api.cancel(attacker.token(), reserved.reservationId());
		this.run.cancels.add(byOwner);
		this.run.report.check("Only the owner can cancel", byVictim.status() == 404 && byOwner.status() == 200,
				"other user -> " + byVictim.outcome() + ", owner -> " + byOwner.outcome());
	}

	/**
	 * Owners cancel won seats while other users try to book the same seats. Each seat
	 * ends with at most one new owner, and nothing returns 5xx.
	 */
	void cancelRacingRebookers(OnSaleWave.Result wave) throws Exception {
		List<String> seats = wave.winners().keySet().stream().sorted().limit(this.run.profile.cancelSeats).toList();
		List<Run.User> rebookers = this.run.takeUsers(seats.size() * this.run.profile.rebookers);
		List<Callable<Api.Result>> tasks = new ArrayList<>();
		List<String> seatOfTask = new ArrayList<>();
		List<Boolean> isCancel = new ArrayList<>();
		int next = 0;
		for (String seat : seats) {
			String reservationId = this.run.expectedConfirmed.get(seat);
			Run.User owner = wave.winners().get(seat);
			tasks.add(() -> this.run.api.cancel(owner.token(), reservationId));
			seatOfTask.add(seat);
			isCancel.add(true);
			for (int r = 0; r < this.run.profile.rebookers; r++) {
				Run.User rebooker = rebookers.get(next++);
				tasks.add(() -> reserve(rebooker, seat, newKey()));
				seatOfTask.add(seat);
				isCancel.add(false);
			}
		}
		long start = System.nanoTime();
		List<Api.Result> results = Run.concurrently(tasks);
		this.run.report.scenario("cancel vs rebookers", results, Run.millisSince(start));

		Map<String, Integer> rebooked = new HashMap<>();
		boolean cancelsOk = true;
		for (int i = 0; i < results.size(); i++) {
			Api.Result result = results.get(i);
			String seat = seatOfTask.get(i);
			if (isCancel.get(i)) {
				this.run.cancels.add(result);
				cancelsOk &= result.status() == 200;
				if (result.status() == 200) {
					this.run.expectedConfirmed.remove(seat, result.reservationId());
				}
				continue;
			}
			this.run.reserves.add(result);
			if (result.clientError() != null) {
				this.run.uncertainSeats.add(seat);
			}
			if (result.status() == 201) {
				rebooked.merge(seat, 1, Integer::sum);
				this.run.expectedConfirmed.put(seat, result.reservationId());
			}
		}
		long serverErrors = results.stream().filter((r) -> r.status() >= 500).count();
		boolean atMostOneEach = rebooked.values().stream().allMatch((n) -> n <= 1);
		this.run.report.check("Cancel racing rebookers: no double-sell, no 5xx",
				cancelsOk && atMostOneEach && serverErrors == 0,
				seats.size() + " seats, " + rebooked.size() + " rebooked, 5xx=" + serverErrors);
	}

	private Api.Result reserve(Run.User user, String seat, String key) {
		return this.run.api.reserve(user.token(), this.run.showId, List.of(seat), key, Map.of());
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
