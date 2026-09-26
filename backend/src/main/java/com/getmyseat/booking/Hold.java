package com.getmyseat.booking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A Customer's temporary, exclusive claim on Seats and General Admission places at one Show, priced when it was
 * made. The inventory it claims is marked in the inventory tables, not here.
 */
@Entity
@Table(name = "hold")
class Hold {

	/** {@code ACTIVE → RELEASED} or {@code ACTIVE → EXPIRED}. */
	enum Status {

		ACTIVE, RELEASED, EXPIRED

	}

	@Id
	private UUID id;

	private UUID customerSubject;

	private UUID showId;

	@Enumerated(EnumType.STRING)
	private Status status;

	private Instant expiresAt;

	private Instant createdAt;

	private long totalPaise;

	private String currency;

	@Version
	private @Nullable Long version;

	@ElementCollection
	@CollectionTable(name = "hold_item", joinColumns = @JoinColumn(name = "hold_id"))
	@OrderColumn(name = "position")
	private List<HoldItem> items = new ArrayList<>();

	protected Hold() {
	}

	/** @param items at least one, all in the same currency */
	Hold(UUID customerSubject, UUID showId, List<HoldItem> items, Instant now, Duration holdTime) {
		this.id = UUID.randomUUID();
		this.customerSubject = customerSubject;
		this.showId = showId;
		this.status = Status.ACTIVE;
		this.createdAt = now;
		this.expiresAt = now.plus(holdTime);
		this.items.addAll(items);
		this.totalPaise = items.stream().mapToLong(HoldItem::totalPaise).sum();
		this.currency = items.getFirst().currency();
		if (items.stream().anyMatch(item -> !item.currency().equals(this.currency))) {
			throw new IllegalArgumentException("A Hold's items must all be in one currency");
		}
	}

	UUID id() {
		return this.id;
	}

	UUID showId() {
		return this.showId;
	}

	boolean ownedBy(UUID subject) {
		return this.customerSubject.equals(subject);
	}

	Status status() {
		return this.status;
	}

	/** Whether the Hold is active and its expiry time has come. */
	boolean dueAt(Instant now) {
		return this.status == Status.ACTIVE && !now.isBefore(this.expiresAt);
	}

	Instant expiresAt() {
		return this.expiresAt;
	}

	Instant createdAt() {
		return this.createdAt;
	}

	long totalPaise() {
		return this.totalPaise;
	}

	String currency() {
		return this.currency;
	}

	List<HoldItem> items() {
		return List.copyOf(this.items);
	}

}
