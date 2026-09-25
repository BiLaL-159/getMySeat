package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.ConflictException;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The thing an Organizer sells, independent of when or where it happens. {@code DRAFT → PUBLISHED}; the owner can
 * edit it in either status. Concurrent changes are caught by the {@link Version version}.
 */
@Entity
@Table(name = "event")
class Event {

	enum Status {

		DRAFT, PUBLISHED

	}

	enum Category {

		MUSIC, COMEDY, THEATRE, DANCE, SPORTS, CONFERENCE, WORKSHOP, FAMILY, OTHER

	}

	/** What an Organizer can edit. @param language an ISO 639-1 code such as {@code en} */
	record Details(String title, String description, Category category, String language) {
	}

	@Id
	private UUID id;

	private UUID ownerSubject;

	private String title;

	private String description;

	@Enumerated(EnumType.STRING)
	private Category category;

	private String language;

	@Enumerated(EnumType.STRING)
	private Status status;

	private Instant createdAt;

	private @Nullable Instant publishedAt;

	@Version
	private @Nullable Long version;

	protected Event() {
	}

	Event(UUID ownerSubject, Details details, Instant now) {
		this.id = UUID.randomUUID();
		this.ownerSubject = ownerSubject;
		this.status = Status.DRAFT;
		this.createdAt = now;
		change(details);
	}

	void change(Details details) {
		this.title = details.title();
		this.description = details.description();
		this.category = details.category();
		this.language = details.language();
	}

	/** @throws ConflictException if the Event is already published */
	void publish(Instant now) {
		if (this.status != Status.DRAFT) {
			throw new ConflictException("This Event is already published.");
		}
		this.status = Status.PUBLISHED;
		this.publishedAt = now;
	}

	boolean isOwnedBy(UUID subject) {
		return this.ownerSubject.equals(subject);
	}

	UUID id() {
		return this.id;
	}

	UUID ownerSubject() {
		return this.ownerSubject;
	}

	String title() {
		return this.title;
	}

	String description() {
		return this.description;
	}

	Category category() {
		return this.category;
	}

	String language() {
		return this.language;
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

}
