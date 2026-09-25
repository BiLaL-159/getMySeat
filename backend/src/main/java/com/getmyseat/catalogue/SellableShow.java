package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * What other modules need to sell a Show: whether it's on sale, when it starts, and each Section of its Venue with
 * its kind, capacity, Seats and Section Price. A snapshot, read through {@link ShowCatalogue}.
 * @param sections every Section of the Show's Venue, in layout order
 */
public record SellableShow(UUID id, boolean published, Instant startsAt, List<SellableSection> sections) {

	public enum Kind {

		SEATED, GENERAL_ADMISSION

	}

	/**
	 * @param capacity how many people a General Admission Section holds; {@code null} for a Seated Section
	 * @param seats the Seats of a Seated Section, in the order they were defined; empty for General Admission
	 * @param price the Section Price; {@code null} only while a draft Show is still being priced
	 */
	public record SellableSection(UUID id, Kind kind, @Nullable Integer capacity, List<SellableSeat> seats,
			@Nullable Price price) {

		public List<UUID> seatIds() {
			return this.seats.stream().map(SellableSeat::id).toList();
		}

	}

	/** A Seat as a Customer sees it on a ticket, such as row {@code A}, seat {@code 12}. */
	public record SellableSeat(UUID id, String rowLabel, int number) {
	}

	/** @param amountPaise whole paise, so {@code 50000} is ₹500 */
	public record Price(long amountPaise, String currency) {
	}

}
