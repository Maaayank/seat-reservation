/**
 * Metrics that let you watch a burst and reconcile it with the API: counters for
 * confirmed, declined (by reason) and cancelled reservations, and DB-backed gauges of
 * seats by state. Exposed at {@code /actuator/prometheus}.
 */
package com.paytm.seats.observability;
