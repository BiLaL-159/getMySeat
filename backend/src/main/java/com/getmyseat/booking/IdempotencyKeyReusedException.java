package com.getmyseat.booking;

import java.util.Map;

import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.ProblemTypes;

/**
 * The Customer sent an {@code Idempotency-Key} they used before with a different Hold request. Returned as
 * {@code 409} with the {@link ProblemTypes#IDEMPOTENCY_KEY_REUSED idempotency-key-reused} type.
 */
class IdempotencyKeyReusedException extends ConflictException {

	IdempotencyKeyReusedException() {
		super("You used that Idempotency-Key for a different Hold request. Send a new key for each new request.",
				ProblemTypes.IDEMPOTENCY_KEY_REUSED, Map.of());
	}

}
