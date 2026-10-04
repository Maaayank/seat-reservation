package com.paytm.seats.reservation;

import java.util.UUID;

/** One seat of one show. */
record SeatKey(UUID showId, String label) {
}
