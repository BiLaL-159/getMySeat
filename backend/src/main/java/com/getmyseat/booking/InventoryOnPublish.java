package com.getmyseat.booking;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.catalogue.ShowCatalogue;
import com.getmyseat.catalogue.ShowPublished;

/** Gives a Show its inventory in the transaction that publishes it, so a published Show always has some. */
@Component
class InventoryOnPublish {

	private final ShowCatalogue catalogue;

	private final ShowInventory inventory;

	InventoryOnPublish(ShowCatalogue catalogue, ShowInventory inventory) {
		this.catalogue = catalogue;
		this.inventory = inventory;
	}

	@EventListener
	@Transactional(propagation = Propagation.MANDATORY)
	void on(ShowPublished event) {
		this.inventory.create(this.catalogue.sellableShow(event.showId()).orElseThrow());
	}

}
