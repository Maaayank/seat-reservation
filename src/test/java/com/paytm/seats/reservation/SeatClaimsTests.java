package com.paytm.seats.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SeatClaimsTests {

	private final SeatClaims claims = new SeatClaims();

	private final UUID show = UUID.randomUUID();

	@Test
	void onlyOneHolderPerSeatAtATime() throws Exception {
		AtomicInteger inside = new AtomicInteger();
		AtomicInteger maxInside = new AtomicInteger();
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			tasks.add(() -> {
				try (SeatClaims.Claim claim = this.claims.acquire(this.show, List.of("A1"), Duration.ofSeconds(10))) {
					int now = inside.incrementAndGet();
					maxInside.accumulateAndGet(now, Math::max);
					Thread.sleep(1);
					inside.decrementAndGet();
					return claim.acquired();
				}
			});
		}
		List<Boolean> acquired = ReservationFixtures.concurrently(tasks);
		assertThat(acquired).containsOnly(true);
		assertThat(maxInside.get()).isEqualTo(1);
		assertThat(this.claims.inFlight()).isZero();
	}

	@Test
	void overlappingMultiSeatClaimsInSortedOrderDoNotDeadlock() throws Exception {
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			tasks.add(() -> {
				try (SeatClaims.Claim claim = this.claims.acquire(this.show, List.of("A1", "A2", "A3"),
						Duration.ofSeconds(10))) {
					return claim.acquired();
				}
			});
			tasks.add(() -> {
				try (SeatClaims.Claim claim = this.claims.acquire(this.show, List.of("A2", "A3"), Duration.ofSeconds(10))) {
					return claim.acquired();
				}
			});
		}
		assertThat(ReservationFixtures.concurrently(tasks)).containsOnly(true);
		assertThat(this.claims.inFlight()).isZero();
	}

	@Test
	void timesOutWithoutLeakingAndFreesPartialLocks() throws Exception {
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(1);
		// Another request (thread) holds A2 for the whole test.
		Thread holder = Thread.ofVirtual().start(() -> {
			try (SeatClaims.Claim claim = this.claims.acquire(this.show, List.of("A2"), Duration.ofSeconds(1))) {
				held.countDown();
				done.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		held.await();

		try (SeatClaims.Claim waiter = this.claims.acquire(this.show, List.of("A1", "A2"), Duration.ofMillis(50))) {
			assertThat(waiter.acquired()).isFalse();
		}
		// A1 was taken, then given back by the timed-out claim.
		try (SeatClaims.Claim other = this.claims.acquire(this.show, List.of("A1"), Duration.ofMillis(50))) {
			assertThat(other.acquired()).isTrue();
		}

		done.countDown();
		holder.join();
		assertThat(this.claims.inFlight()).isZero();
	}

}
