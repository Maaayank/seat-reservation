package com.paytm.seats.reservation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * L2: in-memory record of seats this instance saw confirmed. A hit lets a
 * request be declined with no DB work. It is only a hint: a miss, an eviction
 * or a restart just falls through to L1 and the DB.
 *
 * <p>Each entry remembers which reservation and user own the seat, so:
 * <ul>
 * <li>a seat owned by the requester is never fast-declined (it may be an
 * idempotent retry that must replay its 201);</li>
 * <li>a cancel removes only its own entry, never a newer owner's.</li>
 * </ul>
 *
 * <p>Ordering race: "mark sold" runs after the reserve commits and "mark
 * released" after the cancel commits, but the threads can arrive in either
 * order. Release first writes a tombstone for the reservation, then removes
 * the entry; mark-sold checks the tombstone inside the same per-key
 * {@code compute}. Either way the end state is "not sold", never a stale hit.
 */
@Component
class SoldSeats {

	private final Cache<SeatKey, Owner> sold = Caffeine.newBuilder().maximumSize(500_000).build();

	private final Cache<UUID, Boolean> released = Caffeine.newBuilder()
		.expireAfterWrite(Duration.ofMinutes(10))
		.maximumSize(500_000)
		.build();

	/** True if any seat is known to be owned by a user other than {@code userId}. */
	boolean anyOwnedByOther(UUID showId, Collection<String> labels, String userId) {
		for (String label : labels) {
			Owner owner = this.sold.getIfPresent(new SeatKey(showId, label));
			if (owner != null && !owner.userId().equals(userId)) {
				return true;
			}
		}
		return false;
	}

	void markSold(UUID showId, Collection<String> labels, UUID reservationId, String userId) {
		ConcurrentMap<SeatKey, Owner> map = this.sold.asMap();
		Owner owner = new Owner(reservationId, userId);
		for (String label : labels) {
			map.compute(new SeatKey(showId, label),
					(key, current) -> (this.released.getIfPresent(reservationId) != null) ? current : owner);
		}
	}

	void markReleased(UUID showId, Collection<String> labels, UUID reservationId) {
		this.released.put(reservationId, Boolean.TRUE);
		ConcurrentMap<SeatKey, Owner> map = this.sold.asMap();
		for (String label : labels) {
			map.computeIfPresent(new SeatKey(showId, label),
					(key, current) -> current.reservationId().equals(reservationId) ? null : current);
		}
	}

	record Owner(UUID reservationId, String userId) {
	}

}
