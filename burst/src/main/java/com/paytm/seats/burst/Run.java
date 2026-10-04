package com.paytm.seats.burst;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * State shared by all scenarios of one run: the show, the users, and the client's ledger
 * of what it was told. The final reconciliation compares this ledger with the server.
 */
final class Run {

	final Api api;

	final Profile profile;

	final Report report = new Report();

	/** Fixed seed: the same profile produces the same request mix every run. */
	final Random random = new Random(42);

	final String showId;

	/** Every reserve and cancel response of the run. */
	final List<Api.Result> reserves = Collections.synchronizedList(new ArrayList<>());

	final List<Api.Result> cancels = Collections.synchronizedList(new ArrayList<>());

	/** Seat -> reservation id, as the client believes the final state should be. */
	final Map<String, String> expectedConfirmed = new ConcurrentHashMap<>();

	/** Seats targeted by a request whose outcome the client never learned. */
	final Set<String> uncertainSeats = ConcurrentHashMap.newKeySet();

	private final Iterator<User> users;

	Run(Api api, Profile profile, String showId, Map<String, String> tokens) {
		this.api = api;
		this.profile = profile;
		this.showId = showId;
		this.users = tokens.entrySet().stream().map((e) -> new User(e.getKey(), e.getValue())).iterator();
	}

	/** Hands out fresh users in order; no user is used by two scenarios. */
	List<User> takeUsers(int count) {
		List<User> taken = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			taken.add(this.users.next());
		}
		return taken;
	}

	User takeUser() {
		return this.users.next();
	}

	record User(String id, String token) {
	}

	/**
	 * Starts every task at the same instant on virtual threads; results in task order.
	 */
	static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
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

	static double millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000.0;
	}

}
