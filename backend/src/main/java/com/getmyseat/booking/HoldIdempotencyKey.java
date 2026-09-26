package com.getmyseat.booking;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The {@code Idempotency-Key} of a Hold creation that succeeded, and a hash of its request, so a retry with the same
 * key and request gets that Hold back.
 */
@Entity
@Table(name = "hold_idempotency_key")
class HoldIdempotencyKey implements Persistable<HoldIdempotencyKey.Id> {

	/** Keys are scoped per Customer. */
	@Embeddable
	record Id(UUID customerSubject, String idempotencyKey) {
	}

	@EmbeddedId
	private Id id;

	private String requestHash;

	private UUID holdId;

	private Instant createdAt;

	/** Always inserted, never merged: saving a key someone else has just written must fail, not overwrite it. */
	@Transient
	private boolean isNew = true;

	protected HoldIdempotencyKey() {
	}

	HoldIdempotencyKey(Id id, String requestHash, UUID holdId, Instant now) {
		this.id = id;
		this.requestHash = requestHash;
		this.holdId = holdId;
		this.createdAt = now;
	}

	@Override
	public Id getId() {
		return this.id;
	}

	@Override
	public boolean isNew() {
		return this.isNew;
	}

	@PostLoad
	void loaded() {
		this.isNew = false;
	}

	/** Whether the request with this key had the given hash. */
	boolean madeBy(String requestHash) {
		return this.requestHash.equals(requestHash);
	}

	UUID holdId() {
		return this.holdId;
	}

}
