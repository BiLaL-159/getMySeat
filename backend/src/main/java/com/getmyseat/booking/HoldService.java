package com.getmyseat.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * most one active Hold per Show, so a new Hold releases the old one in the same transaction. Every creation carries
 * the Customer's {@code Idempotency-Key}, so a retry gets the original Hold back and claims nothing.
 */
@Service
class HoldService {

	static final int MAX_TICKETS = 10;

	/** How long a retry with an {@code Idempotency-Key} gets the original Hold back. */
	static final Duration KEY_RETENTION = Duration.ofHours(24);

	/** The unique index that allows one active Hold per Customer per Show. */
	private static final String ONE_ACTIVE_HOLD = "hold_one_active_per_customer_show";

	/** The primary key that allows one use of an {@code Idempotency-Key} per Customer. */
	private static final String ONE_USE_PER_KEY = "hold_idempotency_key_pkey";

	/**
	 * A request with the same {@code Idempotency-Key} committed while this one was running, so this one's
	 * transaction rolled back. Replay the key in a new transaction.
	 */
	static class IdempotencyKeyTakenException extends RuntimeException {

		IdempotencyKeyTakenException() {
			super("Another request with the same Idempotency-Key committed first");
		}

	}

	/** Some General Admission places in one Section, as asked for. */
	record Places(UUID sectionId, int quantity) {
	}

	/** Some General Admission places in one Section at one Show, to give back. */
	private record ShowPlaces(UUID sectionId, UUID showId, int quantity) {

		ShowPlaces plus(ShowPlaces other) {
			return new ShowPlaces(this.sectionId, this.showId, this.quantity + other.quantity);
		}

	}

	private final ShowCatalogue catalogue;

	private final ShowInventory inventory;

	private final HoldRepository holds;

	private final HoldIdempotencyKeyRepository keys;

	private final HoldProperties properties;

	private final Clock clock;

	HoldService(ShowCatalogue catalogue, ShowInventory inventory, HoldRepository holds,
			HoldIdempotencyKeyRepository keys, HoldProperties properties, Clock clock) {
		this.catalogue = catalogue;
		this.inventory = inventory;
		this.holds = holds;
		this.keys = keys;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Makes a Hold, or with a key the Customer used before for the same request, returns that Hold as it is now. The
	 * key is written in the Hold's transaction, so a request that fails leaves no key behind.
	 * @param idempotencyKey the Customer's key for this request
	 * @throws NotFoundException for a draft or unknown Show
	 * @throws InvalidRequestException for the first Seat or General Admission item that breaks a rule
	 * @throws ConflictException if the Show has started, or the Customer is making another Hold for it right now
	 * @throws IdempotencyKeyReusedException if the Customer used the key for a different request
	 * @throws IdempotencyKeyTakenException if another request with the key committed while this one was running;
	 * nothing was held, and {@link #replay} returns that request's Hold
	 * @throws InventoryUnavailableException naming everything that couldn't be held; the Customer's previous Hold
	 * for the Show is still active
	 */
	@Transactional
	HoldResponse create(UUID showId, Caller customer, String idempotencyKey, List<UUID> seats, List<Places> places) {
		HoldIdempotencyKey.Id key = new HoldIdempotencyKey.Id(customer.subject(), idempotencyKey);
		String requestHash = requestHash(showId, seats, places);
		Optional<HoldIdempotencyKey> used = this.keys.findById(key);
		if (used.isPresent()) {
			return replayed(used.get(), requestHash, customer);
		}
		SellableShow show = this.catalogue.sellableShow(showId)
			.filter(SellableShow::published)
			.orElseThrow(() -> new NotFoundException("No Show with that id."));
		List<HoldItem> items = checkedAndPriced(show, seats, places);
		Instant now = now();
		if (!now.isBefore(show.startsAt())) {
			throw new ConflictException("The Show has already started.");
		}
		Hold hold = new Hold(customer.subject(), show.id(), items, now, this.properties.holdTime());
		// Before the old Hold is released: a concurrent request with the same key waits here until this one ends, so
		// it can never release the Hold this one makes.
		try {
			this.keys.saveAndFlush(new HoldIdempotencyKey(key, requestHash, hold.id(), now));
		}
		catch (DataIntegrityViolationException ex) {
			if (violated(ex, ONE_USE_PER_KEY)) {
				throw new IdempotencyKeyTakenException();
			}
			throw ex;
		}
		this.holds.findActive(customer.subject(), show.id()).ifPresent(old -> end(old, old.dueAt(now) ? Hold.Status.EXPIRED : Hold.Status.RELEASED));
		try {
			hold = this.holds.saveAndFlush(hold);
		}
		catch (DataIntegrityViolationException ex) {
			// Another of this Customer's Holds for the Show committed after we looked for one to release.
			if (violated(ex, ONE_ACTIVE_HOLD)) {
				throw new ConflictException(
						"You were making another Hold for this Show at the same moment, so nothing was held. Try again.");
			}
			throw ex;
		}
		claim(hold, seats, places);
		return HoldResponse.of(hold);
	}

	/**
	 * The Hold that a request with the Customer's key made, as it is now, after {@link #create} lost a race for the
	 * key.
	 * @throws IdempotencyKeyReusedException if the key was used for a different request
	 */
	@Transactional
	HoldResponse replay(UUID showId, Caller customer, String idempotencyKey, List<UUID> seats, List<Places> places) {
		HoldIdempotencyKey used = this.keys.findById(new HoldIdempotencyKey.Id(customer.subject(), idempotencyKey))
			.orElseThrow(() -> new IllegalStateException("The Idempotency-Key that won the race isn't there"));
		return replayed(used, requestHash(showId, seats, places), customer);
	}

	/**
	 * An active Hold past its expiry time expires now, and gives back its inventory.
	 * @throws NotFoundException unless the caller owns the Hold
	 */
	@Transactional
	HoldResponse find(UUID id, Caller customer) {
		return current(owned(id, customer));
	}

	/**
	 * An active Hold past its expiry time expires now, gives back its inventory, and isn't found.
	 * @throws NotFoundException if the caller has no active Hold for the Show
	 */
	@Transactional(noRollbackFor = NotFoundException.class)
	HoldResponse mine(UUID showId, Caller customer) {
		Instant now = now();
		return this.holds.findActive(customer.subject(), showId)
			.filter(hold -> !expireIfDue(hold, now))
			.map(HoldResponse::of)
			.orElseThrow(() -> new NotFoundException("You have no active Hold for that Show."));
	}

	/**
	 * Releases the caller's Hold and gives back its inventory. An active Hold past its expiry time expires instead.
	 * @throws NotFoundException unless the caller owns the Hold
	 * @throws ConflictException if the Hold has expired or otherwise isn't active
	 */
	@Transactional(noRollbackFor = ConflictException.class)
	HoldResponse release(UUID id, Caller customer) {
		Hold hold = owned(id, customer);
		if (expireIfDue(hold, now()) || hold.status() == Hold.Status.EXPIRED) {
			throw new ConflictException("The Hold has expired.");
		}
		if (!end(hold, Hold.Status.RELEASED)) {
			throw new ConflictException("The Hold isn't active.");
		}
		return HoldResponse.of(this.holds.findById(id).orElseThrow());
	}

	/**
	 * Expires up to {@code limit} active Holds past their expiry time and gives back their inventory, skipping Holds
	 * that another transaction has locked.
	 * @return how many Holds expired
	 */
	@Transactional
	int expireDue(int limit) {
		List<Hold> due = this.holds.lockDue(now(), limit);
		if (due.isEmpty()) {
			return 0;
		}
		List<UUID> ids = due.stream().map(Hold::id).toList();
		// Read before the transition clears the persistence context.
		List<ShowPlaces> places = placesOf(due);
		// Every one of them is locked by this transaction and still active, so every one expires.
		this.holds.expire(ids);
		giveBack(ids, places);
		return due.size();
	}

	/** The key's Hold, expiring it if it is due, as {@link #find} does. Claims nothing. */
	private HoldResponse replayed(HoldIdempotencyKey used, String requestHash, Caller customer) {
		if (!used.madeBy(requestHash)) {
			throw new IdempotencyKeyReusedException();
		}
		return current(this.holds.findById(used.holdId()).orElseThrow());
	}

	/** The Hold as it is now: an active Hold past its expiry time expires first. */
	private HoldResponse current(Hold hold) {
		if (expireIfDue(hold, now())) {
			hold = this.holds.findById(hold.id()).orElseThrow();
		}
		return HoldResponse.of(hold);
	}

	/**
	 * Deletes the {@code Idempotency-Key}s written more than {@link #KEY_RETENTION} ago, so a later request with one
	 * of them makes a new Hold.
	 * @return how many keys were deleted
	 */
	@Transactional
	int forgetOldKeys() {
		return this.keys.deleteCreatedBefore(now().minus(KEY_RETENTION));
	}

	private Hold owned(UUID id, Caller customer) {
		return this.holds.findById(id)
			.filter(hold -> hold.ownedBy(customer.subject()))
			.orElseThrow(() -> new NotFoundException("No Hold with that id."));
	}

	private Instant now() {
		return this.clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	/**
	 * Expires the Hold if it is active and its expiry time has come. Clears the persistence context when it does, so
	 * re-read the Hold afterwards.
	 * @return whether the Hold was due, whether or not this call was the one that expired it
	 */
	private boolean expireIfDue(Hold hold, Instant now) {
		if (!hold.dueAt(now)) {
			return false;
		}
		end(hold, Hold.Status.EXPIRED);
		return true;
	}

	/**
	 * {@code ACTIVE → RELEASED} or {@code ACTIVE → EXPIRED} with a conditional update, then gives back the inventory
	 * only if this call made the transition, so nothing is given back twice. A replacement takes these locks before
	 * the new Hold's, so it can deadlock with another Hold; the database then aborts one of them, which the caller
	 * sees as a retryable {@code 409}.
	 * @return whether the Hold was active
	 */
	private boolean end(Hold hold, Hold.Status outcome) {
		// Read before the transition clears the persistence context.
		List<ShowPlaces> places = placesOf(List.of(hold));
		if (this.holds.end(hold.id(), outcome) == 0) {
			return false;
		}
		giveBack(List.of(hold.id()), places);
		return true;
	}

	/**
	 * Gives back the inventory of Holds that have just ended: Seats first, then General Admission Sections in id
	 * order, as when claiming.
	 * @param places from {@link #placesOf}
	 */
	private void giveBack(List<UUID> holds, List<ShowPlaces> places) {
		this.inventory.releaseSeats(holds);
		places.forEach(given -> this.inventory.releasePlaces(given.showId(), given.sectionId(), given.quantity()));
	}

	/** The Holds' General Admission places, one entry per Section and Show, in Section id order. */
	private static List<ShowPlaces> placesOf(List<Hold> holds) {
		return holds.stream()
			.flatMap(hold -> hold.items()
				.stream()
				.filter(item -> item.kind() == HoldItem.Kind.GENERAL_ADMISSION)
				.map(item -> new ShowPlaces(item.sectionId(), hold.showId(), item.quantity())))
			.collect(Collectors.toMap(given -> List.of(given.sectionId(), given.showId()), given -> given,
					ShowPlaces::plus))
			.values()
			.stream()
			.sorted(Comparator.comparing(ShowPlaces::sectionId).thenComparing(ShowPlaces::showId))
			.toList();
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

	private static boolean violated(DataIntegrityViolationException ex, String constraint) {
		return ex.getCause() instanceof ConstraintViolationException violation
				&& constraint.equals(violation.getConstraintName());
	}

	/**
	 * Identifies a Hold request by its Show and what it asks for, whatever order the items come in: SHA-256, in hex,
	 * of the Show id, the sorted Seat ids and the General Admission items sorted by Section.
	 */
	private static String requestHash(UUID showId, List<UUID> seats, List<Places> places) {
		String request = showId + "\nseats:" + seats.stream().sorted().map(UUID::toString).collect(Collectors.joining(","))
				+ "\ngeneralAdmission:" + places.stream()
					.sorted(Comparator.comparing(Places::sectionId).thenComparingInt(Places::quantity))
					.map(item -> item.sectionId() + "=" + item.quantity())
					.collect(Collectors.joining(","));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Every JVM has SHA-256", ex);
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
