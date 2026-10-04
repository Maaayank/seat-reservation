package com.paytm.seats.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Fingerprint of what a reserve request asks for: the show and the set of seats. Seat
 * order does not matter. Same key + different fingerprint → 409.
 */
final class RequestHash {

	private RequestHash() {
	}

	static String of(UUID showId, List<String> seats) {
		String canonical = showId + "\n" + String.join(",", seats.stream().sorted().toList());
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
