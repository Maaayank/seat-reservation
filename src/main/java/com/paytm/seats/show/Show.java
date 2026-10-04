package com.paytm.seats.show;

import java.util.UUID;

/** Show definition. Immutable after creation. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
}
