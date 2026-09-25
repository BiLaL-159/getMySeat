package com.getmyseat.booking;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.ProblemTypes;

/**
 * Some of the inventory a Hold asked for is gone, so nothing was held. Returned as {@code 409} with the
 * {@link ProblemTypes#INVENTORY_UNAVAILABLE inventory-unavailable} type and what was missing.
 */
class InventoryUnavailableException extends ConflictException {

	/** @param available how many General Admission places are left, fewer than were asked for */
	record UnavailableSection(UUID sectionId, int available) {
	}

	InventoryUnavailableException(List<UUID> unavailableSeats, List<UnavailableSection> unavailableSections) {
		this("Some of the Seats or General Admission places you asked for are no longer available, so nothing was held.",
				unavailableSeats, unavailableSections);
	}

	private InventoryUnavailableException(String message, List<UUID> unavailableSeats,
			List<UnavailableSection> unavailableSections) {
		super(message, ProblemTypes.INVENTORY_UNAVAILABLE,
				Map.of("unavailableSeats", unavailableSeats, "unavailableSections", unavailableSections));
	}

	/** The database gave up on the Hold because other Holds for the same inventory were in flight. */
	static InventoryUnavailableException contended() {
		return new InventoryUnavailableException(
				"Other Customers were holding the same inventory at the same moment, so nothing was held. Try again.",
				List.of(), List.of());
	}

}
