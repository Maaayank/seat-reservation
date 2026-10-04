package com.paytm.seats.reservation.layers;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** L1: the one read the layers make. Outside any transaction, takes no locks, never blocks. */
@Repository
class SeatOwnership {

	private final JdbcClient jdbc;

	SeatOwnership(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Is any of these seats held or confirmed by another user? Seats the
	 * requester owns do not count, so an idempotent retry still reaches the
	 * transaction and replays.
	 */
	boolean anyTakenByOther(UUID showId, List<String> labels, String userId) {
		return this.jdbc.sql("""
				SELECT EXISTS (
				  SELECT 1 FROM seats
				   WHERE show_id = ? AND label = ANY(?)
				     AND owner_user_id IS DISTINCT FROM ?
				     AND (status = 'CONFIRMED' OR (status = 'HELD' AND hold_expires_at > now()))
				)
				""").params(showId, labels.toArray(String[]::new), userId).query(Boolean.class).single();
	}

}
