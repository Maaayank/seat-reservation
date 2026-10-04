package com.paytm.seats.reservation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * L3: at most one in-flight DB attempt per seat in this instance. Requests
 * for a hot seat wait here on cheap virtual threads instead of each holding a
 * pooled DB connection while blocked on the row lock.
 *
 * <p>Locks are fair and taken in sorted label order, so multi-seat claims
 * cannot deadlock. Entries are reference counted and removed when unused.
 * On timeout the claim gives up its locks and the caller proceeds to the DB
 * anyway: correctness never depends on this layer.
 */
@Component
class SeatClaims {

	private final ConcurrentHashMap<SeatKey, Entry> entries = new ConcurrentHashMap<>();

	/** @param sortedLabels labels in ascending order */
	Claim acquire(UUID showId, List<String> sortedLabels, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		List<SeatKey> held = new ArrayList<>(sortedLabels.size());
		for (String label : sortedLabels) {
			SeatKey key = new SeatKey(showId, label);
			Entry entry = retain(key);
			boolean locked = false;
			try {
				locked = entry.lock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			finally {
				if (!locked) {
					release(key);
				}
			}
			if (!locked) {
				unlockAll(held);
				return Claim.timedOut();
			}
			held.add(key);
		}
		return new Claim(this, held);
	}

	int inFlight() {
		return this.entries.size();
	}

	private Entry retain(SeatKey key) {
		return this.entries.compute(key, (k, e) -> {
			Entry entry = (e != null) ? e : new Entry();
			entry.refs++;
			return entry;
		});
	}

	private void release(SeatKey key) {
		this.entries.computeIfPresent(key, (k, e) -> (--e.refs == 0) ? null : e);
	}

	private void unlockAll(List<SeatKey> keys) {
		for (int i = keys.size() - 1; i >= 0; i--) {
			SeatKey key = keys.get(i);
			this.entries.get(key).lock.unlock();
			release(key);
		}
	}

	private static final class Entry {

		final ReentrantLock lock = new ReentrantLock(true);

		int refs;

	}

	/** Holds the per-seat locks until closed. {@link #acquired()} is false after a timeout. */
	static final class Claim implements AutoCloseable {

		private final SeatClaims owner;

		private final List<SeatKey> held;

		private Claim(SeatClaims owner, List<SeatKey> held) {
			this.owner = owner;
			this.held = held;
		}

		static Claim timedOut() {
			return new Claim(null, List.of());
		}

		static Claim none() {
			return new Claim(null, List.of());
		}

		boolean acquired() {
			return this.owner != null;
		}

		@Override
		public void close() {
			if (this.owner != null) {
				this.owner.unlockAll(this.held);
			}
		}

	}

}
