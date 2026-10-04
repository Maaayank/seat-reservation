/**
 * Shows and their seats. A show and its seat labels never change after creation, so
 * {@link com.paytm.seats.show.ShowCatalog} can cache them. Seat state lives in one row
 * per seat, so available + held + confirmed == total holds by construction; {@code GET
 * /shows/{id}} reads all seats in one query, so its counts come from one snapshot.
 */
package com.paytm.seats.show;
