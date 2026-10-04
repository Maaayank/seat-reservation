package com.paytm.seats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Test helper: release many tasks at the same instant. */
public final class Concurrency {

	private Concurrency() {
	}

	/** Runs all tasks on virtual threads behind one start gate; results in task order. */
	public static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
		CountDownLatch gate = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(executor.submit(() -> {
					gate.await();
					return task.call();
				}));
			}
			gate.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

}
