package com.paytm.seats.reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for the reserve transaction (T1, docs/DISCOVERY.md §4.3.3). Every
 * method is one statement. Callers must run them inside one transaction and
 * in this order: idempotency → quota → seats (sorted) → writes. One global
 * lock order means no deadlock cycle.
 */
@Repository
class ReservationRepository {

	private final JdbcClient jdbc;

	ReservationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Step 1. Claims the key. Returns false if the key already exists. If a
	 * parallel transaction holds the same key, Postgres makes this insert wait
	 * until that transaction ends, so the caller then sees its final result.
	 */
	boolean claimIdempotencyKey(String userId, String key, String requestHash, UUID showId) {
		return this.jdbc.sql("""
				INSERT INTO idempotency_keys (user_id, key, request_hash, show_id)
				VALUES (?, ?, ?, ?)
				ON CONFLICT (user_id, key) DO NOTHING
				""").params(userId, key, requestHash, showId).update() == 1;
	}

	Optional<StoredKey> findIdempotencyKey(String userId, String key) {
		return this.jdbc.sql("SELECT request_hash, response_json FROM idempotency_keys WHERE user_id = ? AND key = ?")
			.params(userId, key)
			.query((rs, n) -> new StoredKey(rs.getString("request_hash"), rs.getString("response_json")))
			.optional();
	}

	/**
	 * Step 2. Adds {@code seats} to the user's count for the show, only if the
	 * result stays within {@code limit}. The row lock serialises one user's
	 * parallel reserves for one show. Returns false when the limit would be
	 * exceeded. Requires {@code seats <= limit} (checked before the transaction).
	 */
	boolean reserveQuota(UUID showId, String userId, int seats, int limit) {
		return this.jdbc.sql("""
				INSERT INTO user_show_quota (show_id, user_id, seats_owned)
				VALUES (?, ?, ?)
				ON CONFLICT (show_id, user_id) DO UPDATE
				   SET seats_owned = user_show_quota.seats_owned + EXCLUDED.seats_owned
				 WHERE user_show_quota.seats_owned + EXCLUDED.seats_owned <= ?
				""").params(showId, userId, seats, limit).update() == 1;
	}

	/**
	 * Step 3. Locks the requested seat rows in label order (deterministic, so
	 * two multi-seat requests cannot deadlock) and returns their latest state.
	 * After a lock wait, READ COMMITTED re-reads the newest row version, so a
	 * seat confirmed by the lock holder is seen as taken.
	 */
	List<LockedSeat> lockSeats(UUID showId, String[] labels) {
		return this.jdbc.sql("""
				SELECT label,
				       (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= now())) AS free
				FROM seats
				WHERE show_id = ? AND label = ANY(?)
				ORDER BY label
				FOR UPDATE
				""")
			.params(showId, labels)
			.query((rs, n) -> new LockedSeat(rs.getString("label"), rs.getBoolean("free")))
			.list();
	}

	/** Step 4. */
	void insertReservation(UUID reservationId, UUID showId, String userId, String[] labels, long amountPaise) {
		this.jdbc.sql("""
				INSERT INTO reservations (id, show_id, user_id, seat_labels, amount_paise, status)
				VALUES (?, ?, ?, ?, ?, 'CONFIRMED')
				""").params(reservationId, showId, userId, labels, amountPaise).update();
	}

	/** Step 5. Rows are already locked by step 3. */
	int confirmSeats(UUID showId, String[] labels, UUID reservationId, String userId) {
		return this.jdbc.sql("""
				UPDATE seats
				   SET status = 'CONFIRMED', reservation_id = ?, owner_user_id = ?,
				       hold_expires_at = NULL, updated_at = now()
				 WHERE show_id = ? AND label = ANY(?)
				""").params(reservationId, userId, showId, labels).update();
	}

	/** Step 6. Stores the response so a retry replays it exactly. */
	void completeIdempotencyKey(String userId, String key, UUID reservationId, String responseJson) {
		this.jdbc.sql("""
				UPDATE idempotency_keys SET reservation_id = ?, response_json = ?
				 WHERE user_id = ? AND key = ?
				""").params(reservationId, responseJson, userId, key).update();
	}

	/** Cancel step 1. Locks the reservation row; parallel cancels of it queue here. */
	Optional<StoredReservation> lockReservation(UUID reservationId) {
		return this.jdbc.sql("""
				SELECT id, show_id, user_id, seat_labels, amount_paise, status
				FROM reservations WHERE id = ?
				FOR UPDATE
				""")
			.param(reservationId)
			.query((rs, n) -> new StoredReservation(rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class),
					rs.getString("user_id"), List.of((String[]) rs.getArray("seat_labels").getArray()),
					rs.getLong("amount_paise"), rs.getString("status")))
			.optional();
	}

	/** Cancel step 2. Same lock order as reserve: quota row before seat rows. */
	void releaseQuota(UUID showId, String userId, int seats) {
		int updated = this.jdbc.sql("""
				UPDATE user_show_quota SET seats_owned = seats_owned - ?
				 WHERE show_id = ? AND user_id = ?
				""").params(seats, showId, userId).update();
		if (updated != 1) {
			throw new IllegalStateException("missing quota row for " + userId + " on show " + showId);
		}
	}

	/**
	 * Cancel step 3. Frees only the seats that still point at this
	 * reservation, locking them in label order (same order as reserve). A seat
	 * that already belongs to someone else is never touched.
	 */
	int releaseSeats(UUID showId, UUID reservationId) {
		return this.jdbc.sql("""
				WITH locked AS (
				  SELECT label FROM seats
				   WHERE show_id = ? AND reservation_id = ?
				   ORDER BY label
				   FOR UPDATE
				)
				UPDATE seats s
				   SET status = 'AVAILABLE', reservation_id = NULL, owner_user_id = NULL,
				       hold_expires_at = NULL, updated_at = now()
				  FROM locked
				 WHERE s.show_id = ? AND s.label = locked.label
				""").params(showId, reservationId, showId).update();
	}

	/** Cancel step 4. */
	void markCancelled(UUID reservationId) {
		this.jdbc.sql("UPDATE reservations SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?")
			.param(reservationId)
			.update();
	}

	record StoredReservation(UUID id, UUID showId, String userId, List<String> seats, long amountPaise,
			String status) {
	}

	record StoredKey(String requestHash, String responseJson) {
	}

	record LockedSeat(String label, boolean free) {
	}

}
