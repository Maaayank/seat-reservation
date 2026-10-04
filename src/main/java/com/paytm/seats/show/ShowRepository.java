package com.paytm.seats.show;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

	private final JdbcClient jdbc;

	public ShowRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(Show show) {
		this.jdbc.sql("""
				INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats)
				VALUES (?, ?, ?, ?, ?)
				""")
			.params(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats())
			.update();
	}

	/** Inserts all seats in one round trip. {@code position} keeps the creation order. */
	public void insertSeats(UUID showId, List<String> labels) {
		this.jdbc.sql("""
				INSERT INTO seats (show_id, label, position)
				SELECT ?, t.label, t.pos
				FROM unnest(?::text[]) WITH ORDINALITY AS t(label, pos)
				""")
			.params(showId, labels.toArray(String[]::new))
			.update();
	}

	public Optional<Show> findById(UUID id) {
		return this.jdbc.sql("""
				SELECT id, name, price_paise, per_user_limit, total_seats
				FROM shows WHERE id = ?
				""")
			.param(id)
			.query((rs, n) -> new Show(rs.getObject("id", UUID.class), rs.getString("name"),
					rs.getLong("price_paise"), rs.getInt("per_user_limit"), rs.getInt("total_seats")))
			.optional();
	}

	public List<String> findSeatLabels(UUID showId) {
		return this.jdbc.sql("SELECT label FROM seats WHERE show_id = ?").param(showId).query(String.class).list();
	}

	/**
	 * All seats of a show in one statement, so the result is one consistent
	 * snapshot. An expired hold reads as available.
	 */
	public List<ShowView.SeatView> findSeats(UUID showId) {
		return this.jdbc.sql("""
				SELECT label,
				       CASE WHEN status = 'HELD' AND hold_expires_at <= now() THEN 'AVAILABLE'
				            ELSE status END AS effective_status
				FROM seats
				WHERE show_id = ?
				ORDER BY position
				""")
			.param(showId)
			.query((rs, n) -> new ShowView.SeatView(rs.getString("label"),
					SeatStatus.valueOf(rs.getString("effective_status"))))
			.list();
	}

}
