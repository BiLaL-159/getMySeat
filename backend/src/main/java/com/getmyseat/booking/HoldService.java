package com.getmyseat.booking;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.access.Caller;
import com.getmyseat.booking.BookingConfiguration.HoldProperties;
import com.getmyseat.booking.InventoryUnavailableException.UnavailableSection;
import com.getmyseat.catalogue.SellableShow;
import com.getmyseat.catalogue.SellableShow.Price;
import com.getmyseat.catalogue.SellableShow.SellableSeat;
import com.getmyseat.catalogue.SellableShow.SellableSection;
import com.getmyseat.catalogue.ShowCatalogue;
import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.NotFoundException;

/**
 * Creates, reads and releases Holds. A Hold claims its inventory with conditional updates in one transaction, all or
 * nothing (ADR 0003): if anything asked for is gone, the transaction rolls back and nothing is held. A Customer has at
 * most one active Hold per Show, so a new Hold releases the old one in the same transaction.
 */
@Service
class HoldService {

	static final int MAX_TICKETS = 10;

	/** The unique index that allows one active Hold per Customer per Show. */
	private static final String ONE_ACTIVE_HOLD = "hold_one_active_per_customer_show";

	/** Some General Admission places in one Section, as asked for. */
	record Places(UUID sectionId, int quantity) {
	}

	private final ShowCatalogue catalogue;

	private final ShowInventory inventory;

	private final HoldRepository holds;

	private final HoldProperties properties;

	private final Clock clock;

	HoldService(ShowCatalogue catalogue, ShowInventory inventory, HoldRepository holds, HoldProperties properties,
			Clock clock) {
		this.catalogue = catalogue;
		this.inventory = inventory;
		this.holds = holds;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * @throws NotFoundException for a draft or unknown Show
	 * @throws InvalidRequestException for the first Seat or General Admission item that breaks a rule
	 * @throws ConflictException if the Show has started, or the Customer is making another Hold for it right now
	 * @throws InventoryUnavailableException naming everything that couldn't be held; the Customer's previous Hold
	 * for the Show is still active
	 */
	@Transactional
	HoldResponse create(UUID showId, Caller customer, List<UUID> seats, List<Places> places) {
		SellableShow show = this.catalogue.sellableShow(showId)
			.filter(SellableShow::published)
			.orElseThrow(() -> new NotFoundException("No Show with that id."));
		List<HoldItem> items = checkedAndPriced(show, seats, places);
		Instant now = this.clock.instant().truncatedTo(ChronoUnit.MICROS);
		if (!now.isBefore(show.startsAt())) {
			throw new ConflictException("The Show has already started.");
		}
		this.holds.findActive(customer.subject(), show.id()).ifPresent(this::release);
		Hold hold;
		try {
			hold = this.holds.saveAndFlush(new Hold(customer.subject(), show.id(), items, now,
					this.properties.holdTime()));
		}
		catch (DataIntegrityViolationException ex) {
			// Another of this Customer's Holds for the Show committed after we looked for one to release.
			if (ex.getCause() instanceof ConstraintViolationException violation
					&& ONE_ACTIVE_HOLD.equals(violation.getConstraintName())) {
				throw new ConflictException(
						"You were making another Hold for this Show at the same moment, so nothing was held. Try again.");
			}
			throw ex;
		}
		claim(hold, seats, places);
		return HoldResponse.of(hold);
	}

	/** @throws NotFoundException unless the caller owns the Hold */
	@Transactional(readOnly = true)
	HoldResponse find(UUID id, Caller customer) {
		return HoldResponse.of(owned(id, customer));
	}

	/** @throws NotFoundException if the caller has no active Hold for the Show */
	@Transactional(readOnly = true)
	HoldResponse mine(UUID showId, Caller customer) {
		return this.holds.findActive(customer.subject(), showId)
			.map(HoldResponse::of)
			.orElseThrow(() -> new NotFoundException("You have no active Hold for that Show."));
	}

	/**
	 * Releases the caller's Hold and gives back its inventory.
	 * @throws NotFoundException unless the caller owns the Hold
	 * @throws ConflictException if the Hold isn't active
	 */
	@Transactional
	HoldResponse release(UUID id, Caller customer) {
		if (!release(owned(id, customer))) {
			throw new ConflictException("The Hold isn't active.");
		}
		return HoldResponse.of(this.holds.findById(id).orElseThrow());
	}

	private Hold owned(UUID id, Caller customer) {
		return this.holds.findById(id)
			.filter(hold -> hold.ownedBy(customer.subject()))
			.orElseThrow(() -> new NotFoundException("No Hold with that id."));
	}

	/**
	 * {@code ACTIVE → RELEASED} with a conditional update, then gives back the inventory only if this call made the
	 * transition, so nothing is given back twice. General Admission Sections go in id order, as when claiming. A
	 * replacement takes these locks before the new Hold's, so it can deadlock with another Hold; the database then
	 * aborts one of them, which the caller sees as a retryable {@code 409}.
	 * @return whether the Hold was active
	 */
	private boolean release(Hold hold) {
		// Read before the release clears the persistence context.
		List<HoldItem> items = hold.items();
		if (this.holds.release(hold.id()) == 0) {
			return false;
		}
		this.inventory.releaseSeats(hold.id());
		items.stream()
			.filter(item -> item.kind() == HoldItem.Kind.GENERAL_ADMISSION)
			.sorted(Comparator.comparing(HoldItem::sectionId))
			.forEach(item -> this.inventory.releasePlaces(hold.showId(), item.sectionId(), item.quantity()));
		return true;
	}

	/** Seats first, then General Admission Sections in id order, so every Hold takes its locks in the same order. */
	private void claim(Hold hold, List<UUID> seats, List<Places> places) {
		Set<UUID> claimed = new HashSet<>(this.inventory.holdSeats(hold.showId(), hold.id(), seats));
		List<UUID> unavailableSeats = seats.stream().filter(seat -> !claimed.contains(seat)).toList();
		Map<UUID, Integer> placesLeft = new HashMap<>();
		places.stream().sorted(Comparator.comparing(Places::sectionId)).forEach(item -> {
			OptionalInt left = this.inventory.holdPlaces(hold.showId(), item.sectionId(), item.quantity());
			left.ifPresent(available -> placesLeft.put(item.sectionId(), available));
		});
		if (!unavailableSeats.isEmpty() || !placesLeft.isEmpty()) {
			List<UnavailableSection> unavailableSections = places.stream()
				.filter(item -> placesLeft.containsKey(item.sectionId()))
				.map(item -> new UnavailableSection(item.sectionId(), placesLeft.get(item.sectionId())))
				.toList();
			throw new InventoryUnavailableException(unavailableSeats, unavailableSections);
		}
	}

	/** Checks the request against the Show's layout and prices it, in layout order. */
	private static List<HoldItem> checkedAndPriced(SellableShow show, List<UUID> seats, List<Places> places) {
		long tickets = seats.size() + places.stream().mapToLong(Places::quantity).sum();
		if (tickets < 1 || tickets > MAX_TICKETS) {
			throw new InvalidRequestException("tickets", "must be between 1 and " + MAX_TICKETS
					+ " tickets per Hold: each Seat is one ticket and each General Admission item is its quantity");
		}
		Map<UUID, SellableSection> sections = new HashMap<>();
		Map<UUID, SellableSection> seatSections = new HashMap<>();
		for (SellableSection section : show.sections()) {
			sections.put(section.id(), section);
			section.seats().forEach(seat -> seatSections.put(seat.id(), section));
		}
		Set<UUID> requestedSeats = new HashSet<>();
		for (int i = 0; i < seats.size(); i++) {
			UUID seat = seats.get(i);
			String field = "seats[" + i + "]";
			if (!requestedSeats.add(seat)) {
				throw new InvalidRequestException(field, "is repeated; ask for each Seat once");
			}
			if (sections.containsKey(seat)) {
				throw new InvalidRequestException(field,
						"is a Section, not a Seat; ask for General Admission places under generalAdmission");
			}
			if (!seatSections.containsKey(seat)) {
				throw new InvalidRequestException(field, "isn't a Seat at this Show's Venue");
			}
		}
		Map<UUID, Integer> requestedPlaces = new HashMap<>();
		for (int i = 0; i < places.size(); i++) {
			Places item = places.get(i);
			String field = "generalAdmission[" + i + "].sectionId";
			if (requestedPlaces.putIfAbsent(item.sectionId(), item.quantity()) != null) {
				throw new InvalidRequestException(field, "is repeated; ask for each Section once");
			}
			SellableSection section = sections.get(item.sectionId());
			if (section == null) {
				throw new InvalidRequestException(field, seatSections.containsKey(item.sectionId())
						? "is a Seat, not a Section; ask for Seats under seats" : "isn't a Section at this Show's Venue");
			}
			if (section.kind() != SellableShow.Kind.GENERAL_ADMISSION) {
				throw new InvalidRequestException(field,
						"is a Seated Section; ask for its Seats by id under seats");
			}
		}
		List<HoldItem> items = new ArrayList<>();
		for (SellableSection section : show.sections()) {
			Price price = section.price();
			if (price == null) {
				throw new IllegalStateException("Published Show " + show.id() + " has no price for " + section.id());
			}
			for (SellableSeat seat : section.seats()) {
				if (requestedSeats.contains(seat.id())) {
					items.add(HoldItem.seat(section.id(), seat, price));
				}
			}
			Integer quantity = requestedPlaces.get(section.id());
			if (quantity != null) {
				items.add(HoldItem.places(section.id(), quantity, price));
			}
		}
		return items;
	}

}
