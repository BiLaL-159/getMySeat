package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What the Event endpoints return.
 * @param ownerSubject the Organizer who created the Event; only shown to them
 * @param version pass it back when editing, so a concurrent edit is detected
 */
record EventResponse(UUID id, String title, String description, Event.Category category, String language,
		Event.Status status, @Nullable UUID ownerSubject, Instant createdAt, @Nullable Instant publishedAt,
		long version) {

	static EventResponse of(Event event) {
		return new EventResponse(event.id(), event.title(), event.description(), event.category(), event.language(),
				event.status(), event.ownerSubject(), event.createdAt(), event.publishedAt(), event.version());
	}

	/** Without the owner's Keycloak subject, for anyone other than the owner. */
	EventResponse withoutOwner() {
		return new EventResponse(this.id, this.title, this.description, this.category, this.language, this.status,
				null, this.createdAt, this.publishedAt, this.version);
	}

}
