package com.paytm.seats.show;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-through cache of immutable show data (price, limit, seat labels). Lets
 * the reserve path validate a request without a DB round trip. Safe to cache:
 * shows and their seat labels never change after creation.
 */
@Component
public class ShowCatalog {

	private final ShowRepository shows;

	private final Cache<UUID, Entry> cache = Caffeine.newBuilder().maximumSize(1_000).build();

	public ShowCatalog(ShowRepository shows) {
		this.shows = shows;
	}

	public Optional<Entry> find(UUID showId) {
		Entry cached = this.cache.getIfPresent(showId);
		if (cached != null) {
			return Optional.of(cached);
		}
		Optional<Entry> loaded = this.shows.findById(showId)
			.map((show) -> new Entry(show, Set.copyOf(this.shows.findSeatLabels(showId))));
		loaded.ifPresent((entry) -> this.cache.put(showId, entry));
		return loaded;
	}

	public record Entry(Show show, Set<String> seatLabels) {
	}

}
