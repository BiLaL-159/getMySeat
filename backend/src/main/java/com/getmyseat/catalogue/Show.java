package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.ConflictException;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One occurrence of an Event at an approved Venue and start time, with a Section Price per Section of that Venue.
 * {@code DRAFT → PUBLISHED}; once published, its Venue, start time and prices are locked. Changing its prices bumps
 * the {@link Version version} too, so a publish racing a price change is caught.
 */
@Entity
@Table(name = "show")
class Show {

	enum Status {

		DRAFT, PUBLISHED

	}

	@Id
	private UUID id;

	private UUID eventId;

	private UUID venueId;

	private Instant startsAt;

	@Enumerated(EnumType.STRING)
	private Status status;

	private Instant createdAt;

	private @Nullable Instant publishedAt;

	@Version
	private @Nullable Long version;

	@ElementCollection
	@BatchSize(size = 50)
	@CollectionTable(name = "section_price", joinColumns = @JoinColumn(name = "show_id"))
	private Set<SectionPrice> prices = new HashSet<>();

	protected Show() {
	}

	Show(UUID eventId, UUID venueId, Instant startsAt, Instant now) {
		this.id = UUID.randomUUID();
		this.eventId = eventId;
		this.venueId = venueId;
		this.startsAt = startsAt;
		this.status = Status.DRAFT;
		this.createdAt = now;
	}

	/**
	 * Moves the draft Show; prices for Sections the new Venue doesn't have are dropped.
	 * @param sections the ids of the new Venue's Sections
	 */
	void reschedule(UUID venueId, Collection<UUID> sections, Instant startsAt) {
		requireDraft();
		this.venueId = venueId;
		this.startsAt = startsAt;
		this.prices.removeIf(price -> !sections.contains(price.sectionId()));
	}

	/** Replaces every Section Price at once; the caller has checked they belong to this Show's Venue. */
	void reprice(Collection<SectionPrice> prices) {
		requireDraft();
		this.prices.clear();
		this.prices.addAll(prices);
	}

	/**
	 * @param sections the ids of the Venue's Sections
	 * @throws ConflictException naming the first unmet precondition
	 */
	void publish(Event event, Venue venue, Collection<UUID> sections, Instant now) {
		if (this.status != Status.DRAFT) {
			throw new ConflictException("This Show is already published.");
		}
		if (event.status() != Event.Status.PUBLISHED) {
			throw new ConflictException("Publish the Event before publishing its Shows.");
		}
		if (venue.status() != Venue.Status.APPROVED) {
			throw new ConflictException("The Show's Venue isn't approved.");
		}
		if (!this.startsAt.isAfter(now)) {
			throw new ConflictException("The Show's start time has passed. Move it to a future time first.");
		}
		Set<UUID> priced = new HashSet<>();
		this.prices.forEach(price -> priced.add(price.sectionId()));
		if (!priced.containsAll(sections)) {
			throw new ConflictException("Set a Section Price for every Section of the Venue before publishing.");
		}
		this.status = Status.PUBLISHED;
		this.publishedAt = now;
	}

	/** @throws ConflictException once the Show is published */
	void requireDraft() {
		if (this.status != Status.DRAFT) {
			throw new ConflictException("This Show is published, so its Venue, start time and prices can't change.");
		}
	}

	UUID id() {
		return this.id;
	}

	UUID eventId() {
		return this.eventId;
	}

	UUID venueId() {
		return this.venueId;
	}

	Instant startsAt() {
		return this.startsAt;
	}

	Status status() {
		return this.status;
	}

	Instant createdAt() {
		return this.createdAt;
	}

	@Nullable Instant publishedAt() {
		return this.publishedAt;
	}

	long version() {
		return (this.version != null) ? this.version : 0;
	}

	/** Ordered by Section id, so responses are stable. */
	List<SectionPrice> prices() {
		return this.prices.stream().sorted(Comparator.comparing(SectionPrice::sectionId)).toList();
	}

}
