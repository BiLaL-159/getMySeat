package com.getmyseat.booking;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.catalogue.SellableShow.Price;
import com.getmyseat.catalogue.SellableShow.SellableSeat;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * What a Hold claims: one Seat, or a quantity of General Admission places in one Section. The Section Price, and a
 * Seat's row and number, are copied at Hold time.
 * @param quantity always 1 for a Seat
 * @param pricePaise the Section Price of one ticket, in whole paise
 */
@Embeddable
record HoldItem(@Enumerated(EnumType.STRING) Kind kind, UUID sectionId, @Nullable UUID seatId,
		@Nullable String rowLabel, @Nullable Integer seatNumber, int quantity, long pricePaise, String currency) {

	enum Kind {

		SEAT, GENERAL_ADMISSION

	}

	static HoldItem seat(UUID section, SellableSeat seat, Price price) {
		return new HoldItem(Kind.SEAT, section, seat.id(), seat.rowLabel(), seat.number(), 1, price.amountPaise(),
				price.currency());
	}

	static HoldItem places(UUID section, int quantity, Price price) {
		return new HoldItem(Kind.GENERAL_ADMISSION, section, null, null, null, quantity, price.amountPaise(),
				price.currency());
	}

	long totalPaise() {
		return this.pricePaise * this.quantity;
	}

}
