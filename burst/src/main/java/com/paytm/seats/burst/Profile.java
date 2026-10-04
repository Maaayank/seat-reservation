package com.paytm.seats.burst;

import java.util.ArrayList;
import java.util.List;

/**
 * Load shapes. {@code SMOKE} is safe for a free-tier demo; {@code FULL} puts about 20,000
 * requests in the on-sale wave.
 *
 * <p>
 * Seat map: {@code rows} rows of {@code perRow} seats (A1..A50, B1..). Rows A-C are the
 * "good" seats. The last row is kept out of the wave and used by the follow-up scenarios
 * (per-user limit, spoof, key reuse), so their outcomes do not depend on the wave.
 */
enum Profile {

	// @formatter:off
	//     rows perRow hotSeats hotUsers retryGroups retries stampede limitUsers cancelSeats rebookers maxInFlight
	SMOKE(  5,   50,    3,       100,     20,         5,      1_100,   2,         5,          10,       300),
	FULL(  21,   50,   10,       500,    200,         5,     14_000,   4,        10,          20,     2_000);
	// @formatter:on

	final int rows;

	final int perRow;

	final int hotSeats;

	final int hotUsers;

	final int retryGroups;

	final int retries;

	final int stampede;

	final int limitUsers;

	final int cancelSeats;

	final int rebookers;

	final int maxInFlight;

	Profile(int rows, int perRow, int hotSeats, int hotUsers, int retryGroups, int retries, int stampede,
			int limitUsers, int cancelSeats, int rebookers, int maxInFlight) {
		this.rows = rows;
		this.perRow = perRow;
		this.hotSeats = hotSeats;
		this.hotUsers = hotUsers;
		this.retryGroups = retryGroups;
		this.retries = retries;
		this.stampede = stampede;
		this.limitUsers = limitUsers;
		this.cancelSeats = cancelSeats;
		this.rebookers = rebookers;
		this.maxInFlight = maxInFlight;
	}

	/** Distinct users the run needs, plus a few spares for fallbacks. */
	int usersNeeded() {
		int wave = this.hotSeats * this.hotUsers + this.retryGroups + this.stampede;
		int spoof = 2;
		int spares = 5;
		return wave + this.limitUsers + spoof + this.cancelSeats * this.rebookers + spares;
	}

	List<String> allSeats() {
		List<String> labels = new ArrayList<>(this.rows * this.perRow);
		for (int r = 0; r < this.rows; r++) {
			for (int c = 1; c <= this.perRow; c++) {
				labels.add(rowName(r) + c);
			}
		}
		return labels;
	}

	/** Seat {@code n} (1-based) of the last row, which the wave never touches. */
	String reservedSeat(int n) {
		return rowName(this.rows - 1) + n;
	}

	static String rowName(int row) {
		return (row < 26) ? String.valueOf((char) ('A' + row)) : "R" + row;
	}

}
