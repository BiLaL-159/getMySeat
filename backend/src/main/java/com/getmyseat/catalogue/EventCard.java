package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.catalogue.ShowBrowseResponses.Price;

/**
 * A published Event in search results, with what a Customer needs to pick one.
 * @param nextShow the soonest upcoming published Show, among those matching the search's city and dates if given;
 * {@code null} if there is none
 * @param lowestPrice the lowest Section Price across the same Shows; {@code null} if there are none
 */
record EventCard(UUID id, String title, String description, Event.Category category, String language,
		Event.Status status, @Nullable Instant publishedAt, @Nullable NextShow nextShow, @Nullable Price lowestPrice) {

	static EventCard of(Event event, EventCards.@Nullable Browse browse) {
		return new EventCard(event.id(), event.title(), event.description(), event.category(), event.language(),
				event.status(), event.publishedAt(), (browse != null) ? browse.nextShow() : null,
				(browse != null) ? browse.lowestPrice() : null);
	}

	/**
	 * Where and when an Event is next on.
	 * @param timeZone the Venue's IANA time zone, for showing the start time locally
	 */
	record NextShow(UUID id, Instant startsAt, String venueName, String city, String timeZone) {
	}

}
