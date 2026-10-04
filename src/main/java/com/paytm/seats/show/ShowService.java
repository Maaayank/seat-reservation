package com.paytm.seats.show;

import com.paytm.seats.common.ApiException;
import com.paytm.seats.common.ErrorCode;
import com.paytm.seats.config.SeatsProperties;
import com.paytm.seats.observability.SeatGauges;
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

	private final SeatGauges gauges;

	public ShowService(ShowRepository shows, SeatsProperties properties, SeatGauges gauges) {
		this.shows = shows;
		this.properties = properties;
		this.gauges = gauges;
	}

	@Transactional
	public ShowView create(CreateShowRequest request) {
		List<String> labels = request.seats();
		if (labels.size() > this.properties.maxSeatsPerShow()) {
			throw new ApiException(ErrorCode.INVALID_SEATS,
					"a show can have at most " + this.properties.maxSeatsPerShow() + " seats");
		}
		Set<String> unique = new HashSet<>(labels);
		if (unique.size() != labels.size()) {
			throw new ApiException(ErrorCode.INVALID_SEATS, "seat labels must be unique");
		}
		int perUserLimit = (request.perUserLimit() != null) ? request.perUserLimit()
				: this.properties.defaultPerUserLimit();
		Show show = new Show(UUID.randomUUID(), request.name(), request.pricePaise(), perUserLimit, labels.size());
		this.shows.insert(show);
		this.shows.insertSeats(show.id(), labels);
		this.gauges.markDirty();
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
		return new ApiException(ErrorCode.SHOW_NOT_FOUND, "show not found");
	}

}
