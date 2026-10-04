package com.paytm.seats.show;

import com.paytm.seats.config.SeatsProperties;
import com.paytm.seats.web.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

	private final ShowRepository shows;

	private final SeatsProperties properties;

	public ShowService(ShowRepository shows, SeatsProperties properties) {
		this.shows = shows;
		this.properties = properties;
	}

	@Transactional
	public ShowView create(CreateShowRequest request) {
		List<String> labels = request.seats();
		if (labels.size() > this.properties.maxSeatsPerShow()) {
			throw ApiException.unprocessable("invalid_seats",
					"a show can have at most " + this.properties.maxSeatsPerShow() + " seats");
		}
		Set<String> unique = new HashSet<>(labels);
		if (unique.size() != labels.size()) {
			throw ApiException.unprocessable("invalid_seats", "seat labels must be unique");
		}
		int perUserLimit = (request.perUserLimit() != null) ? request.perUserLimit()
				: this.properties.defaultPerUserLimit();
		Show show = new Show(UUID.randomUUID(), request.name(), request.pricePaise(), perUserLimit, labels.size());
		this.shows.insert(show);
		this.shows.insertSeats(show.id(), labels);
		List<ShowView.SeatView> seats = labels.stream()
			.map((label) -> new ShowView.SeatView(label, SeatStatus.AVAILABLE))
			.toList();
		return ShowView.of(show, seats);
	}

	public ShowView get(String rawId) {
		UUID id = parseShowId(rawId);
		Show show = this.shows.findById(id).orElseThrow(ShowService::notFound);
		return ShowView.of(show, this.shows.findSeats(id));
	}

	/** Parses a show id from a path; malformed ids are reported as not found. */
	public static UUID parseShowId(String rawId) {
		try {
			return UUID.fromString(rawId);
		}
		catch (IllegalArgumentException ex) {
			throw notFound();
		}
	}

	private static ApiException notFound() {
		return ApiException.notFound("show_not_found", "show not found");
	}

}
