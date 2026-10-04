package com.paytm.seats.reservation;

import com.paytm.seats.common.ApiException;
import com.paytm.seats.common.ErrorCode;
import com.paytm.seats.show.ShowCatalog;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Checks a reserve request before any DB work: the idempotency key and the
 * seat list. Seat labels are checked against the cached show, so an unknown
 * seat never reaches the transaction.
 */
final class ReserveRequestValidator {

	private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

	private ReserveRequestValidator() {
	}

	/** The header wins over the body field. Missing → 400; malformed → 422. */
	static String idempotencyKey(String headerKey, ReserveRequest request) {
		String key = (headerKey != null && !headerKey.isBlank()) ? headerKey
				: (request != null) ? request.idempotencyKey() : null;
		if (key == null || key.isBlank()) {
			throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
					"send an Idempotency-Key header or an idempotency_key field");
		}
		if (!KEY_FORMAT.matcher(key).matches()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST, "idempotency key must be 1-128 chars of [A-Za-z0-9._:-]");
		}
		return key;
	}

	/** Non-empty, unique, known to the show, and within the per-user limit. */
	static List<String> seats(ReserveRequest request, ShowCatalog.Entry show) {
		List<String> seats = (request != null) ? request.seats() : null;
		if (seats == null || seats.isEmpty()) {
			throw new ApiException(ErrorCode.INVALID_SEATS, "seats must not be empty");
		}
		if (seats.stream().anyMatch(Objects::isNull) || new HashSet<>(seats).size() != seats.size()) {
			throw new ApiException(ErrorCode.INVALID_SEATS, "seats must be unique and non-null");
		}
		if (!show.seatLabels().containsAll(seats)) {
			throw new ApiException(ErrorCode.INVALID_SEATS, "unknown seat label for this show");
		}
		int limit = show.show().perUserLimit();
		if (seats.size() > limit) {
			throw new ApiException(ErrorCode.PER_USER_LIMIT, "at most " + limit + " seats per user for this show");
		}
		return List.copyOf(seats);
	}

}
