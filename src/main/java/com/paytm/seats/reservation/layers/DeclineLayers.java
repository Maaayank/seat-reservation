package com.paytm.seats.reservation.layers;

import com.paytm.seats.config.SeatsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fast-decline layers in front of the reserve transaction (docs/DISCOVERY.md §6.2). They
 * only ever decline, and only for seats owned by another user; they never grant a seat.
 * With every layer disabled the service is still correct: the transaction alone decides.
 *
 * <ul>
 * <li>L2 {@link SoldSeats}: in-memory hit, no DB work.</li>
 * <li>L1 read: one non-locking query; never blocks.</li>
 * <li>L3 {@link SeatClaims}: one in-flight DB attempt per seat; waiters re-check L2 when
 * their turn comes, so the losers of a hot seat never touch the DB.</li>
 * </ul>
 */
@Component
public class DeclineLayers {

	public static final String METRIC = "reservations.decline.path";

	private final SeatsProperties.Layers config;

	private final SoldSeats sold;

	private final SeatClaims claims;

	private final SeatOwnership ownership;

	private final Map<String, Counter> declines;

	private final Counter claimTimeouts;

	DeclineLayers(SeatsProperties properties, SoldSeats sold, SeatClaims claims, SeatOwnership ownership,
			MeterRegistry registry) {
		this.config = properties.layers();
		this.sold = sold;
		this.claims = claims;
		this.ownership = ownership;
		this.declines = Map.of("l2_sold_set", counter(registry, "l2_sold_set"), "l1_read", counter(registry, "l1_read"),
				"l3_waiter", counter(registry, "l3_waiter"), "db", counter(registry, "db"));
		this.claimTimeouts = Counter.builder("reservations.seat.claim.timeouts")
			.description("L3 waits that timed out and fell through to the DB")
			.register(registry);
		Gauge.builder("reservations.seat.claims.inflight", claims, SeatClaims::inFlight)
			.description("Seats with an L3 claim held or awaited")
			.register(registry);
	}

	/** L2, in memory. Returns true if the request can be declined as seat_taken now. */
	public boolean declineFromMemory(UUID showId, List<String> seats, String userId) {
		if (this.config.soldSet() && this.sold.anyOwnedByOther(showId, seats, userId)) {
			this.declines.get("l2_sold_set").increment();
			return true;
		}
		return false;
	}

	/** L1, one DB read. Returns true if the request can be declined as seat_taken now. */
	public boolean declineByRead(UUID showId, List<String> seats, String userId) {
		if (this.config.readCheck() && this.ownership.anyTakenByOther(showId, seats, userId)) {
			this.declines.get("l1_read").increment();
			return true;
		}
		return false;
	}

	/** L3. Returns a claim to hold for the whole DB attempt (may be a no-op claim). */
	public SeatClaims.Claim claim(UUID showId, List<String> sortedSeats) {
		if (!this.config.seatClaim()) {
			return SeatClaims.Claim.none();
		}
		try {
			SeatClaims.Claim claim = this.claims.acquire(showId, sortedSeats, this.config.seatClaimTimeout());
			if (!claim.acquired()) {
				this.claimTimeouts.increment();
			}
			return claim;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for seat claim", ex);
		}
	}

	/** After an L3 wait: did the request ahead of us sell one of our seats? */
	public boolean declineAfterWait(SeatClaims.Claim claim, UUID showId, List<String> seats, String userId) {
		if (claim.acquired() && this.config.soldSet() && this.sold.anyOwnedByOther(showId, seats, userId)) {
			this.declines.get("l3_waiter").increment();
			return true;
		}
		return false;
	}

	public void declinedByDatabase() {
		this.declines.get("db").increment();
	}

	/** Call after commit and before the claim is released, so waiters see it. */
	public void confirmed(UUID showId, List<String> seats, UUID reservationId, String userId) {
		if (this.config.soldSet()) {
			this.sold.markSold(showId, seats, reservationId, userId);
		}
	}

	/** Call after a cancel commits. */
	public void released(UUID showId, List<String> seats, UUID reservationId) {
		if (this.config.soldSet()) {
			this.sold.markReleased(showId, seats, reservationId);
		}
	}

	private static Counter counter(MeterRegistry registry, String layer) {
		return Counter.builder(METRIC)
			.description("seat_taken declines by the layer that decided them")
			.tag("layer", layer)
			.register(registry);
	}

}
