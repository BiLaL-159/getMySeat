package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** What the Venue endpoints return. Built inside the service's transaction, since the layout loads lazily. */
final class VenueResponses {

	private VenueResponses() {
	}

	/**
	 * A Venue with its full layout.
	 * @param ownerSubject the proposing Organizer; only shown to them and to Admins
	 * @param decidedBy the deciding Admin; only shown to the owner and to Admins
	 */
	record VenueResponse(UUID id, String name, String address, String city, String timeZone, Venue.Status status,
			@Nullable String rejectionReason, @Nullable UUID ownerSubject, @Nullable Instant submittedAt,
			@Nullable UUID decidedBy, @Nullable Instant decidedAt, Instant createdAt, List<SectionResponse> sections) {

		static VenueResponse of(Venue venue) {
			return new VenueResponse(venue.id(), venue.name(), venue.address(), venue.city(), venue.timeZone(),
					venue.status(), venue.rejectionReason(), venue.ownerSubject(), venue.submittedAt(),
					venue.decidedBy(), venue.decidedAt(), venue.createdAt(),
					venue.sections().stream().map(SectionResponse::of).toList());
		}

		/** Without the Keycloak subjects of the people involved, for anyone other than the owner or an Admin. */
		VenueResponse withoutPeople() {
			return new VenueResponse(this.id, this.name, this.address, this.city, this.timeZone, this.status,
					this.rejectionReason, null, this.submittedAt, null, this.decidedAt, this.createdAt, this.sections);
		}

	}

	/** A Venue in a list, without its layout. */
	record VenueSummary(UUID id, String name, String address, String city, String timeZone, Venue.Status status) {

		static VenueSummary of(Venue venue) {
			return new VenueSummary(venue.id(), venue.name(), venue.address(), venue.city(), venue.timeZone(),
					venue.status());
		}

	}

	/**
	 * @param capacity how many people a General Admission Section holds; {@code null} for a Seated Section
	 * @param seats the Seats of a Seated Section, in the order they were defined; empty for General Admission
	 */
	record SectionResponse(UUID id, String name, Section.Kind kind, @Nullable Integer capacity,
			List<SeatResponse> seats) {

		static SectionResponse of(Section section) {
			return new SectionResponse(section.id(), section.name(), section.kind(), section.capacity(),
					section.seats().stream().map(SeatResponse::of).toList());
		}

	}

	/** @param label the row and number together, such as {@code A12}; unique within the Section */
	record SeatResponse(UUID id, String row, int number, String label) {

		static SeatResponse of(Seat seat) {
			return new SeatResponse(seat.id(), seat.rowLabel(), seat.number(), seat.label());
		}

	}

}
