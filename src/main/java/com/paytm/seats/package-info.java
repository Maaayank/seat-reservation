/**
 * Seat reservation service: sells assigned seats correctly under concurrent load.
 *
 * <h2>Packages (by feature)</h2>
 * <ul>
 * <li>{@code common}: error contract, request id, shared web plumbing.</li>
 * <li>{@code config}: typed settings ({@code seats.*}) and the clock.</li>
 * <li>{@code auth}: bearer tokens for users, admin key for admin routes.</li>
 * <li>{@code show}: create a show, read per-seat status and counts.</li>
 * <li>{@code reservation}: reserve and cancel; the atomic seat decision.</li>
 * <li>{@code reservation.layers}: fast-decline layers in front of the transaction.</li>
 * <li>{@code observability}: business metrics and DB-backed seat gauges.</li>
 * </ul>
 *
 * <h2>Request path</h2> <pre>
 * RequestIdFilter → AdminKeyFilter (admin routes) → BearerTokenFilter (user routes)
 *   → controller → service → repository (JdbcClient, explicit SQL) → PostgreSQL
 * </pre>
 *
 * <h2>Rules that keep it correct</h2>
 * <ul>
 * <li>PostgreSQL is the only authority that grants a seat. Caches and locks in the app
 * may only decline faster.</li>
 * <li>One global lock order: idempotency key → reservation row → quota row → seat rows by
 * label. No two transactions can wait on each other in a cycle.</li>
 * <li>Identity comes only from the token. Money is {@code long} paise.</li>
 * </ul>
 * Design decisions are numbered D1..Dn in docs/DISCOVERY.md.
 */
package com.paytm.seats;
