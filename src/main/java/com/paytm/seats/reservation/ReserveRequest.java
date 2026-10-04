package com.paytm.seats.reservation;

import java.util.List;

/**
 * {@code POST /shows/{id}/reserve} body. There is deliberately no user field:
 * identity comes only from the bearer token, and unknown fields (such as a
 * spoofed {@code user_id}) are ignored. The idempotency key may also be sent
 * as the {@code Idempotency-Key} header, which takes precedence.
 */
public record ReserveRequest(List<String> seats, String idempotencyKey) {
}
