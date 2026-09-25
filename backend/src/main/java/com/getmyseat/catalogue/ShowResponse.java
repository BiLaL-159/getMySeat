package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What the Organizer's Show endpoints return. Built inside the service's transaction, since the prices load lazily.
 * @param prices one Section Price per priced Section of the Venue
 * @param version pass it back when editing, so a concurrent edit is detected
 */
record ShowResponse(UUID id, UUID eventId, UUID venueId, Instant startsAt, Show.Status status,
		List<PriceResponse> prices, Instant createdAt, @Nullable Instant publishedAt, long version) {

	static ShowResponse of(Show show) {
		return new ShowResponse(show.id(), show.eventId(), show.venueId(), show.startsAt(), show.status(),
				show.prices().stream().map(PriceResponse::of).toList(), show.createdAt(), show.publishedAt(),
				show.version());
	}

	/** @param amountPaise whole paise, so {@code 50000} is ₹500 */
	record PriceResponse(UUID sectionId, long amountPaise, String currency) {

		static PriceResponse of(SectionPrice price) {
			return new PriceResponse(price.sectionId(), price.amountPaise(), price.currency());
		}

	}

}
