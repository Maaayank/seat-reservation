package com.paytm.seats.reservation;

import com.paytm.seats.common.ErrorCode;
import com.paytm.seats.show.Show;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The atomic seat decision (T1, docs/DISCOVERY.md §4.3.3). One short READ COMMITTED
 * transaction, one statement per step:
 *
 * <ol>
 * <li>Claim the idempotency key. An existing key means a retry: replay or 409.</li>
 * <li>Add the seats to the user's quota row, only if it stays within the limit.</li>
 * <li>Lock the seat rows in label order and check they are all free.</li>
 * <li>Insert the reservation, confirm the seats, store the replayable response.</li>
 * </ol>
 *
 * Lock order is always key → quota → seats by label (D20), so no two transactions can
 * wait on each other in a cycle. Any decline rolls back everything, including the key, so
 * a declined key can be retried (D9).
 */
@Component
class ReserveTransaction {

	private final ReservationRepository repository;

	private final TransactionTemplate transactions;

	private final ObjectMapper mapper;

	ReserveTransaction(ReservationRepository repository, TransactionTemplate transactions, ObjectMapper mapper) {
		this.repository = repository;
		this.transactions = transactions;
		this.mapper = mapper;
	}

	/**
	 * @param reservation the reservation to create if every check passes; its id and
	 * amount are fixed before the transaction starts
	 */
	ReserveOutcome execute(Show show, String key, String requestHash, ReservationView reservation) {
		ReserveOutcome outcome = this.transactions.execute((tx) -> decide(tx, show, key, requestHash, reservation));
		return Objects.requireNonNull(outcome);
	}

	private ReserveOutcome decide(TransactionStatus tx, Show show, String key, String requestHash,
			ReservationView reservation) {
		String userId = reservation.userId();
		String[] seats = reservation.seats().toArray(String[]::new);

		if (!this.repository.claimIdempotencyKey(userId, key, requestHash, show.id())) {
			tx.setRollbackOnly();
			return replay(userId, key, requestHash);
		}
		if (!this.repository.addToQuota(show.id(), userId, seats.length, show.perUserLimit())) {
			tx.setRollbackOnly();
			return new ReserveOutcome.Declined(ErrorCode.PER_USER_LIMIT,
					"at most " + show.perUserLimit() + " seats per user for this show");
		}
		List<ReservationRepository.LockedSeat> locked = this.repository.lockSeats(show.id(), seats);
		if (locked.size() != seats.length) {
			// Defensive: labels were already checked against the show before the
			// transaction.
			tx.setRollbackOnly();
			return new ReserveOutcome.Declined(ErrorCode.INVALID_SEATS, "unknown seat label");
		}
		if (!locked.stream().allMatch(ReservationRepository.LockedSeat::free)) {
			tx.setRollbackOnly();
			return new ReserveOutcome.Declined(ErrorCode.SEAT_TAKEN, "one or more seats are taken");
		}

		this.repository.insertReservation(reservation);
		this.repository.confirmSeats(show.id(), seats, reservation.reservationId(), userId);
		this.repository.completeIdempotencyKey(userId, key, reservation.reservationId(),
				this.mapper.writeValueAsString(reservation));
		return new ReserveOutcome.Confirmed(reservation);
	}

	/**
	 * The key exists and its transaction has finished: replay it, or 409 if the request
	 * differs.
	 */
	private ReserveOutcome replay(String userId, String key, String requestHash) {
		ReservationRepository.StoredKey stored = this.repository.findIdempotencyKey(userId, key)
			.orElseThrow(() -> new IllegalStateException("idempotency key vanished after conflict"));
		if (!stored.requestHash().equals(requestHash)) {
			return new ReserveOutcome.Declined(ErrorCode.IDEMPOTENCY_KEY_REUSE,
					"idempotency key was already used with a different request");
		}
		if (stored.responseJson() == null) {
			throw new IllegalStateException("committed idempotency key has no stored response");
		}
		return new ReserveOutcome.Replayed(this.mapper.readValue(stored.responseJson(), ReservationView.class));
	}

}
