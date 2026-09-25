package com.getmyseat.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A Hold as its Customer sees it.
 * @param expiresAt when the Hold lets go of its inventory unless it has been paid for
 * @param items Seats and General Admission places, in the Venue's layout order
 * @param totalPaise what the Hold costs in whole paise: each item's price times its quantity
 */
record HoldResponse(UUID id, UUID showId, Hold.Status status, Instant expiresAt, List<HoldItemResponse> items,
		long totalPaise, String currency, Instant createdAt) {

	static HoldResponse of(Hold hold) {
		return new HoldResponse(hold.id(), hold.showId(), hold.status(), hold.expiresAt(),
				hold.items().stream().map(HoldItemResponse::of).toList(), hold.totalPaise(), hold.currency(),
				hold.createdAt());
	}

	/**
	 * One Seat, or some General Admission places in one Section.
	 * @param seatId {@code null} for General Admission
	 * @param rowLabel {@code null} for General Admission
	 * @param seatNumber {@code null} for General Admission
	 * @param quantity always 1 for a Seat
	 * @param pricePaise the Section Price of one ticket, fixed when the Hold was made
	 */
	record HoldItemResponse(HoldItem.Kind kind, UUID sectionId, @Nullable UUID seatId, @Nullable String rowLabel,
			@Nullable Integer seatNumber, int quantity, long pricePaise) {

		static HoldItemResponse of(HoldItem item) {
			return new HoldItemResponse(item.kind(), item.sectionId(), item.seatId(), item.rowLabel(),
					item.seatNumber(), item.quantity(), item.pricePaise());
		}

	}

}
