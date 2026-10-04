package com.paytm.seats.reservation.layers;

import java.util.UUID;

/** One seat of one show. */
record SeatKey(UUID showId, String label) {
}
