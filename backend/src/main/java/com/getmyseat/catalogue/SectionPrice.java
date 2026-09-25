package com.getmyseat.catalogue;

import java.util.UUID;

import jakarta.persistence.Embeddable;

/**
 * The ticket price an Organizer sets for one Section at one Show.
 * @param amountPaise whole paise, above zero
 * @param currency always {@link #INR} for now
 */
@Embeddable
record SectionPrice(UUID sectionId, long amountPaise, String currency) {

	static final String INR = "INR";

}
