package com.getmyseat.booking;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.booking.ShowInventory.GeneralAdmissionLeft;
import com.getmyseat.catalogue.SellableShow;
import com.getmyseat.catalogue.ShowCatalogue;
import com.getmyseat.shared.api.NotFoundException;

/** A published Show's live availability, for anyone. */
@Service
class AvailabilityService {

	private final ShowCatalogue catalogue;

	private final ShowInventory inventory;

	AvailabilityService(ShowCatalogue catalogue, ShowInventory inventory) {
		this.catalogue = catalogue;
		this.inventory = inventory;
	}

	/** @throws NotFoundException for a draft or unknown Show, as the Show itself is */
	@Transactional(readOnly = true)
	ShowAvailability availability(UUID showId) {
		SellableShow show = this.catalogue.sellableShow(showId)
			.filter(SellableShow::published)
			.orElseThrow(() -> new NotFoundException("No Show with that id."));
		Map<UUID, Boolean> seats = this.inventory.seats(show.id());
		Map<UUID, GeneralAdmissionLeft> generalAdmission = this.inventory.generalAdmission(show.id());
		return ShowAvailability.of(show, seats, generalAdmission);
	}

}
