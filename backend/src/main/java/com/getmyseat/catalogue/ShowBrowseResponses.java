package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.catalogue.VenueResponses.SeatResponse;

/** What Customers see when they list an Event's Shows or open one. Built inside the service's transaction. */
final class ShowBrowseResponses {

	private ShowBrowseResponses() {
	}

	/** A Show in an Event's list, with where it happens. */
	record ShowSummary(UUID id, UUID eventId, Instant startsAt, Show.Status status, ShowVenue venue) {

		static ShowSummary of(Show show, Venue venue) {
			return new ShowSummary(show.id(), show.eventId(), show.startsAt(), show.status(), ShowVenue.of(venue));
		}

	}

	/**
	 * Where a Show happens.
	 * @param timeZone the Venue's IANA time zone, for showing the start time locally
	 */
	record ShowVenue(UUID id, String name, String address, String city, String timeZone) {

		static ShowVenue of(Venue venue) {
			return new ShowVenue(venue.id(), venue.name(), venue.address(), venue.city(), venue.timeZone());
		}

	}

	/**
	 * A Show with its Venue and what each Section costs.
	 * @param sections every Section of the Venue, in layout order
	 * @param version pass it back when editing, so a concurrent edit is detected
	 */
	record ShowDetail(UUID id, UUID eventId, Instant startsAt, Show.Status status, ShowVenue venue,
			List<SectionDetail> sections, Instant createdAt, @Nullable Instant publishedAt, long version) {

		static ShowDetail of(Show show, Venue venue) {
			Map<UUID, SectionPrice> prices = new HashMap<>();
			show.prices().forEach(price -> prices.put(price.sectionId(), price));
			return new ShowDetail(show.id(), show.eventId(), show.startsAt(), show.status(),
					ShowVenue.of(venue),
					venue.sections().stream().map(section -> SectionDetail.of(section, prices.get(section.id()))).toList(),
					show.createdAt(), show.publishedAt(), show.version());
		}

	}

	/**
	 * @param price the Section Price at this Show; {@code null} only while a draft Show is still being priced
	 * @param capacity how many people a General Admission Section holds; {@code null} for a Seated Section
	 * @param seats the Seats of a Seated Section, in the order they were defined; empty for General Admission
	 */
	record SectionDetail(UUID id, String name, Section.Kind kind, @Nullable Price price,
			@Nullable Integer capacity, List<SeatResponse> seats) {

		static SectionDetail of(Section section, @Nullable SectionPrice price) {
			return new SectionDetail(section.id(), section.name(), section.kind(),
					(price != null) ? new Price(price.amountPaise(), price.currency()) : null, section.capacity(),
					section.seats().stream().map(SeatResponse::of).toList());
		}

	}

	/** @param amountPaise whole paise, so {@code 50000} is ₹500 */
	record Price(long amountPaise, String currency) {
	}

}
