package com.paytm.seats.show;

import java.util.List;
import java.util.UUID;

/** API view of a show: per-seat status and counts that always sum to the total. */
public record ShowView(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Counts counts,
		List<SeatView> seats) {

	public record SeatView(String label, SeatStatus status) {
	}

	public record Counts(int available, int held, int confirmed, int total) {
	}

	static ShowView of(Show show, List<SeatView> seats) {
		int available = 0;
		int held = 0;
		int confirmed = 0;
		for (SeatView seat : seats) {
			switch (seat.status()) {
				case AVAILABLE -> available++;
				case HELD -> held++;
				case CONFIRMED -> confirmed++;
			}
		}
		return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
				new Counts(available, held, confirmed, seats.size()), seats);
	}

}
