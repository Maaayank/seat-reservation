package com.paytm.seats.reservation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for reserve and cancel. Each method is one statement and must run inside the
 * caller's transaction, in the documented lock order.
 */
@Repository
class ReservationRepository {

	private final JdbcClient jdbc;

	ReservationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---- reserve ------------------------------------------------------------------

	/**
	 * Claims the key; false if it already exists. If a parallel transaction holds the
	 * same key, Postgres makes this insert wait until that transaction ends, so the
	 * caller then sees its final result.
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
	 * Adds {@code seats} to the user's count for the show, only if the result stays
	 * within {@code limit}; false otherwise. The row lock serialises one user's parallel
	 * reserves for one show. Caller guarantees {@code seats <= limit}.
	 */
	boolean addToQuota(UUID showId, String userId, int seats, int limit) {
		return this.jdbc.sql("""
				INSERT INTO user_show_quota (show_id, user_id, seats_owned)
				VALUES (?, ?, ?)
				ON CONFLICT (show_id, user_id) DO UPDATE
				   SET seats_owned = user_show_quota.seats_owned + EXCLUDED.seats_owned
				 WHERE user_show_quota.seats_owned + EXCLUDED.seats_owned <= ?
				""").params(showId, userId, seats, limit).update() == 1;
	}

	/**
	 * Locks the seat rows in label order and returns their latest state. After a lock
	 * wait, READ COMMITTED re-reads the newest row version, so a seat just confirmed by
	 * the lock holder is seen as taken.
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

	void insertReservation(ReservationView reservation) {
		this.jdbc.sql("""
				INSERT INTO reservations (id, show_id, user_id, seat_labels, amount_paise, status)
				VALUES (?, ?, ?, ?, ?, ?)
				""")
			.params(reservation.reservationId(), reservation.showId(), reservation.userId(),
					reservation.seats().toArray(String[]::new), reservation.amountPaise(), reservation.status().name())
			.update();
	}

	/** Rows are already locked by {@link #lockSeats}. */
	void confirmSeats(UUID showId, String[] labels, UUID reservationId, String userId) {
		this.jdbc.sql("""
				UPDATE seats
				   SET status = 'CONFIRMED', reservation_id = ?, owner_user_id = ?,
				       hold_expires_at = NULL, updated_at = now()
				 WHERE show_id = ? AND label = ANY(?)
				""").params(reservationId, userId, showId, labels).update();
	}

	/** Stores the 201 body so a retry with the same key replays it exactly. */
	void completeIdempotencyKey(String userId, String key, UUID reservationId, String responseJson) {
		this.jdbc.sql("""
				UPDATE idempotency_keys SET reservation_id = ?, response_json = ?
				 WHERE user_id = ? AND key = ?
				""").params(reservationId, responseJson, userId, key).update();
	}

	// ---- cancel -------------------------------------------------------------------

	/** Locks the reservation row; parallel cancels of the same reservation queue here. */
	Optional<ReservationView> lockReservation(UUID reservationId) {
		return this.jdbc.sql("""
				SELECT id, show_id, user_id, seat_labels, amount_paise, status
				FROM reservations WHERE id = ?
				FOR UPDATE
				""")
			.param(reservationId)
			.query((rs, n) -> new ReservationView(rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class),
					rs.getString("user_id"), List.of((String[]) rs.getArray("seat_labels").getArray()),
					rs.getLong("amount_paise"), ReservationStatus.valueOf(rs.getString("status"))))
			.optional();
	}

	void removeFromQuota(UUID showId, String userId, int seats) {
		int updated = this.jdbc.sql("""
				UPDATE user_show_quota SET seats_owned = seats_owned - ?
				 WHERE show_id = ? AND user_id = ?
				""").params(seats, showId, userId).update();
		if (updated != 1) {
			throw new IllegalStateException("missing quota row for " + userId + " on show " + showId);
		}
	}

	/**
	 * Frees only the seats that still point at this reservation, locking them in label
	 * order (same order as reserve). A seat that now belongs to someone else is never
	 * touched. Returns the number of seats freed.
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

	void markCancelled(UUID reservationId) {
		this.jdbc.sql("UPDATE reservations SET status = 'CANCELLED', cancelled_at = now() WHERE id = ?")
			.param(reservationId)
			.update();
	}

	record StoredKey(String requestHash, String responseJson) {
	}

	record LockedSeat(String label, boolean free) {
	}

}
