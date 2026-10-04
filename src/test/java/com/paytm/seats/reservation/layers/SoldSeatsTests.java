package com.paytm.seats.reservation.layers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SoldSeatsTests {

	private final SoldSeats sold = new SoldSeats();

	private final UUID show = UUID.randomUUID();

	@Test
	void soldSeatDeclinesOthersButNotItsOwner() {
		this.sold.markSold(this.show, List.of("A1"), UUID.randomUUID(), "alice");
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A1"), "bob")).isTrue();
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A1"), "alice")).isFalse();
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A2"), "bob")).isFalse();
		assertThat(this.sold.anyOwnedByOther(UUID.randomUUID(), List.of("A1"), "bob")).isFalse();
	}

	@Test
	void releaseClearsTheSeat() {
		UUID rid = UUID.randomUUID();
		this.sold.markSold(this.show, List.of("A1", "A2"), rid, "alice");
		this.sold.markReleased(this.show, List.of("A1", "A2"), rid);
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A1", "A2"), "bob")).isFalse();
	}

	@Test
	void lateMarkSoldAfterReleaseLeavesNoStaleEntry() {
		UUID rid = UUID.randomUUID();
		// The cancel's thread finished first; the reserve's thread arrives late.
		this.sold.markReleased(this.show, List.of("A1"), rid);
		this.sold.markSold(this.show, List.of("A1"), rid, "alice");
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A1"), "bob")).isFalse();
	}

	@Test
	void staleReleaseNeverRemovesNewOwner() {
		UUID old = UUID.randomUUID();
		this.sold.markSold(this.show, List.of("A1"), old, "alice");
		this.sold.markReleased(this.show, List.of("A1"), old);
		this.sold.markSold(this.show, List.of("A1"), UUID.randomUUID(), "bob");
		// A repeated (idempotent) cancel of the old reservation arrives again.
		this.sold.markReleased(this.show, List.of("A1"), old);
		assertThat(this.sold.anyOwnedByOther(this.show, List.of("A1"), "carol")).isTrue();
	}

}
