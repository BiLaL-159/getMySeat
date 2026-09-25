package com.getmyseat.catalogue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.VenueResponses.SectionResponse;
import com.getmyseat.catalogue.VenueResponses.VenueResponse;
import com.getmyseat.catalogue.VenueResponses.VenueSummary;
import com.getmyseat.access.Role;
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.NotFoundException;

/**
 * The Venue lifecycle. Ownership is checked against the stored owner subject: someone else's unapproved Venue is
 * {@code 404} and someone else's approved Venue is {@code 403} to change. Every change locks the Venue row.
 */
@Service
class VenueService {

	private final VenueRepository venues;

	VenueService(VenueRepository venues) {
		this.venues = venues;
	}

	@Transactional
	VenueResponse create(Caller organizer, Venue.Details details) {
		return VenueResponse.of(this.venues.save(new Venue(organizer.subject(), checked(details), Instant.now())));
	}

	@Transactional
	VenueResponse change(UUID id, Caller organizer, Venue.Details details) {
		Venue venue = editable(id, organizer);
		venue.change(checked(details));
		return VenueResponse.of(venue);
	}

	@Transactional
	SectionResponse addSection(UUID venueId, Caller organizer, String name, Section.Kind kind, @Nullable Integer capacity,
			List<SeatRow> rows) {
		Venue venue = editable(venueId, organizer);
		Section section = venue.addSection(name, kind, capacity);
		if (!rows.isEmpty()) {
			section.addRows(rows);
		}
		return SectionResponse.of(section);
	}

	@Transactional
	SectionResponse changeSection(UUID venueId, UUID sectionId, Caller organizer, String name, @Nullable Integer capacity) {
		Venue venue = editable(venueId, organizer);
		Section section = venue.renameSection(sectionId, name);
		section.changeCapacity(capacity);
		return SectionResponse.of(section);
	}

	@Transactional
	void removeSection(UUID venueId, UUID sectionId, Caller organizer) {
		editable(venueId, organizer).removeSection(sectionId);
	}

	@Transactional
	SectionResponse addSeats(UUID venueId, UUID sectionId, Caller organizer, List<SeatRow> rows) {
		Section section = editable(venueId, organizer).section(sectionId);
		section.addRows(rows);
		return SectionResponse.of(section);
	}

	@Transactional
	void removeSeat(UUID venueId, UUID sectionId, UUID seatId, Caller organizer) {
		editable(venueId, organizer).section(sectionId).removeSeat(seatId);
	}

	@Transactional
	VenueResponse submit(UUID id, Caller organizer) {
		Venue venue = owned(id, organizer);
		venue.submit(Instant.now());
		return VenueResponse.of(venue);
	}

	@Transactional
	VenueResponse approve(UUID id, Caller admin) {
		Venue venue = forDecision(id);
		venue.approve(admin.subject(), Instant.now());
		return VenueResponse.of(venue);
	}

	@Transactional
	VenueResponse reject(UUID id, Caller admin, String reason) {
		Venue venue = forDecision(id);
		venue.reject(admin.subject(), reason, Instant.now());
		return VenueResponse.of(venue);
	}

	@Transactional(readOnly = true)
	Page<VenueSummary> mine(Caller organizer, Pageable pageable) {
		return this.venues.findByOwnerSubject(organizer.subject(), withTieBreaker(pageable)).map(VenueSummary::of);
	}

	@Transactional(readOnly = true)
	Page<VenueResponse> reviewQueue(Venue.@Nullable Status status, Pageable pageable) {
		Pageable stable = withTieBreaker(pageable);
		Page<Venue> page = (status != null) ? this.venues.findByStatus(status, stable) : this.venues.findAll(stable);
		return page.map(VenueResponse::of);
	}

	@Transactional(readOnly = true)
	Page<VenueSummary> search(@Nullable String q, @Nullable String city, Pageable pageable) {
		return this.venues.findAll(VenueRepository.approved(q, city), withTieBreaker(pageable)).map(VenueSummary::of);
	}

	/** An approved Venue for anyone; the owner also sees it in any other status. */
	@Transactional(readOnly = true)
	VenueResponse visible(UUID id, Optional<Caller> caller) {
		Venue venue = this.venues.findById(id).orElseThrow(VenueService::notFound);
		boolean involved = caller.filter(c -> venue.isOwnedBy(c.subject()) || c.hasRole(Role.ADMIN)).isPresent();
		if (involved) {
			return VenueResponse.of(venue);
		}
		if (venue.status() == Venue.Status.APPROVED) {
			return VenueResponse.of(venue).withoutPeople();
		}
		throw notFound();
	}

	private Venue owned(UUID id, Caller organizer) {
		Venue venue = this.venues.findForUpdate(id).orElseThrow(VenueService::notFound);
		if (!venue.isOwnedBy(organizer.subject())) {
			if (venue.status() == Venue.Status.APPROVED) {
				throw new AccessDeniedException("Only the Organizer who proposed a Venue can change it.");
			}
			throw notFound();
		}
		return venue;
	}

	private Venue editable(UUID id, Caller organizer) {
		Venue venue = owned(id, organizer);
		venue.requireEditable();
		return venue;
	}

	private Venue forDecision(UUID id) {
		return this.venues.findForUpdate(id).orElseThrow(VenueService::notFound);
	}

	private static Venue.Details checked(Venue.Details details) {
		if (!ZoneId.getAvailableZoneIds().contains(details.timeZone())) {
			throw new InvalidRequestException("timeZone", "must be an IANA time zone such as Asia/Kolkata");
		}
		return details;
	}

	/** Breaks ties on id so pages stay stable when sort values repeat. */
	private static Pageable withTieBreaker(Pageable pageable) {
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
	}

	private static NotFoundException notFound() {
		return new NotFoundException("No Venue with that id.");
	}

}
