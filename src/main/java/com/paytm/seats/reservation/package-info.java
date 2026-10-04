/**
 * Reserve and cancel.
 *
 * <ul>
 * <li>{@link com.paytm.seats.reservation.ReservationService}: the reserve flow (validate
 * → fast-decline layers → transaction → metrics and log).</li>
 * <li>{@link com.paytm.seats.reservation.ReserveTransaction}: the atomic decision, one
 * short transaction with a fixed lock order.</li>
 * <li>{@link com.paytm.seats.reservation.CancelService}: owner-only, idempotent
 * cancel.</li>
 * <li>{@link com.paytm.seats.reservation.ReservationRepository}: the SQL, one statement
 * per method.</li>
 * </ul>
 *
 * Semantics: all-or-nothing for multi-seat requests; idempotency keys are per user and
 * only successes are stored; a confirmed seat can only be freed by its own reservation's
 * cancel.
 */
package com.paytm.seats.reservation;
