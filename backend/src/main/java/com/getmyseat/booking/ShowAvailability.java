package com.getmyseat.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.booking.ShowInventory.GeneralAdmissionLeft;
import com.getmyseat.catalogue.SellableShow;
import com.getmyseat.catalogue.SellableShow.SellableSection;

/**
 * What's left to sell at a Show.
 * @param sections every Section of the Show's Venue, in layout order
 */
record ShowAvailability(UUID showId, List<SectionAvailability> sections) {

	static ShowAvailability of(SellableShow show, Map<UUID, Boolean> seats,
			Map<UUID, GeneralAdmissionLeft> generalAdmission) {
		return new ShowAvailability(show.id(), show.sections()
			.stream()
			.map(section -> SectionAvailability.of(section, seats, generalAdmission.get(section.id())))
			.toList());
	}

	/**
	 * @param seats every Seat of a Seated Section, in the order they were defined; empty for General Admission
	 * @param capacity how many people a General Admission Section holds; {@code null} for a Seated Section
	 * @param available how many General Admission places are left; {@code null} for a Seated Section
	 */
	record SectionAvailability(UUID id, SellableShow.Kind kind, List<SeatAvailability> seats,
			@Nullable Integer capacity, @Nullable Integer available) {

		static SectionAvailability of(SellableSection section, Map<UUID, Boolean> seats,
				@Nullable GeneralAdmissionLeft generalAdmission) {
			return new SectionAvailability(section.id(), section.kind(),
					section.seatIds()
						.stream()
						.map(seat -> new SeatAvailability(seat, seats.getOrDefault(seat, false)))
						.toList(),
					(generalAdmission != null) ? generalAdmission.capacity() : null,
					(generalAdmission != null) ? generalAdmission.available() : null);
		}

	}

	/** @param available {@code false} while the Seat is held */
	record SeatAvailability(UUID id, boolean available) {
	}

}
