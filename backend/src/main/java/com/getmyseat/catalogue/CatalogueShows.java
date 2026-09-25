package com.getmyseat.catalogue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.catalogue.SellableShow.Price;
import com.getmyseat.catalogue.SellableShow.SellableSeat;
import com.getmyseat.catalogue.SellableShow.SellableSection;

/** {@link ShowCatalogue} over the catalogue's own Shows and Venues. */
@Service
class CatalogueShows implements ShowCatalogue {

	private final ShowRepository shows;

	private final VenueRepository venues;

	CatalogueShows(ShowRepository shows, VenueRepository venues) {
		this.shows = shows;
		this.venues = venues;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SellableShow> sellableShow(UUID id) {
		return this.shows.findById(id).map(this::sellable);
	}

	private SellableShow sellable(Show show) {
		Map<UUID, Price> prices = new HashMap<>();
		show.prices().forEach(price -> prices.put(price.sectionId(), new Price(price.amountPaise(), price.currency())));
		Venue venue = this.venues.findById(show.venueId()).orElseThrow();
		return new SellableShow(show.id(), show.status() == Show.Status.PUBLISHED, show.startsAt(),
				venue.sections()
					.stream()
					.map(section -> new SellableSection(section.id(), SellableShow.Kind.valueOf(section.kind().name()),
							section.capacity(), section.seats().stream().map(CatalogueShows::sellable).toList(),
							prices.get(section.id())))
					.toList());
	}

	private static SellableSeat sellable(Seat seat) {
		return new SellableSeat(seat.id(), seat.rowLabel(), seat.number());
	}

}
