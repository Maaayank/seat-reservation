/**
 * Fast-decline layers (docs/DISCOVERY.md §6.2, D22). They let the losers of a hot seat
 * get their 409 without a DB transaction:
 * <ul>
 * <li>L2 {@link com.paytm.seats.reservation.layers.SoldSeats}: in-memory record of sold
 * seats.</li>
 * <li>L1 {@link com.paytm.seats.reservation.layers.SeatOwnership}: one non-locking
 * read.</li>
 * <li>L3 {@link com.paytm.seats.reservation.layers.SeatClaims}: one in-flight DB attempt
 * per seat.</li>
 * </ul>
 * They only decline, and only for seats owned by another user. With all of them switched
 * off the service is still correct; the test suite runs both ways.
 */
package com.paytm.seats.reservation.layers;
