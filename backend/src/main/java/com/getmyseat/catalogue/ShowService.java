package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.ShowBrowseResponses.ShowDetail;
import com.getmyseat.catalogue.ShowBrowseResponses.ShowSummary;
import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.NotFoundException;

/**
 * The Show lifecycle. A Show belongs to its Event's owner, and follows the Event's rules: someone else's draft Show
 * is {@code 404} and someone else's published Show is {@code 403} to change. Changes are optimistically locked on
 * the Show's version.
 */
@Service
class ShowService {

	private final ShowRepository shows;

	private final EventService events;

	private final VenueRepository venues;

	ShowService(ShowRepository shows, EventService events, VenueRepository venues) {
		this.shows = shows;
		this.events = events;
		this.venues = venues;
	}

	@Transactional
	ShowResponse schedule(UUID eventId, Caller organizer, UUID venueId, Instant startsAt) {
		Event event = this.events.owned(eventId, organizer);
		Venue venue = approvedVenue(venueId);
		return ShowResponse.of(this.shows.saveAndFlush(new Show(event.id(), venue.id(), startsAt, Instant.now())));
	}

	/**
	 * @param version the version the Organizer last saw
	 * @throws ConflictException if the Show has changed since, or is published
	 */
	@Transactional
	ShowResponse reschedule(UUID id, Caller organizer, UUID venueId, Instant startsAt, long version) {
		Show show = owned(id, organizer);
		show.requireDraft();
		if (show.version() != version) {
			throw changedMeanwhile();
		}
		Venue venue = (venueId.equals(show.venueId())) ? venue(show) : approvedVenue(venueId);
		show.reschedule(venue.id(), sectionIds(venue), startsAt);
		return ShowResponse.of(save(show));
	}

	/**
	 * Replaces every Section Price of the Show.
	 * @throws InvalidRequestException if a price is for a Section outside the Show's Venue, or a Section twice
	 */
	@Transactional
	ShowResponse reprice(UUID id, Caller organizer, List<SectionPrice> prices) {
		Show show = owned(id, organizer);
		show.requireDraft();
		Set<UUID> sections = sectionIds(venue(show));
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < prices.size(); i++) {
			UUID section = prices.get(i).sectionId();
			if (!sections.contains(section)) {
				throw new InvalidRequestException("prices[" + i + "].sectionId", "isn't a Section of the Show's Venue");
			}
			if (!seen.add(section)) {
				throw new InvalidRequestException("prices[" + i + "].sectionId", "already has a price in this request");
			}
		}
		show.reprice(prices);
		return ShowResponse.of(save(show));
	}

	@Transactional
	ShowResponse publish(UUID id, Caller organizer) {
		Show show = owned(id, organizer);
		Venue venue = venue(show);
		show.publish(this.events.event(show.eventId()), venue, sectionIds(venue), Instant.now());
		return ShowResponse.of(save(show));
	}

	/** The Event's Shows: all of them for its owner, the upcoming published ones for anyone else. */
	@Transactional(readOnly = true)
	Page<ShowSummary> ofEvent(UUID eventId, Optional<Caller> caller, Pageable pageable) {
		Event event = this.events.visibleEvent(eventId, caller);
		Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				pageable.getSort().and(Sort.by("id")));
		Page<Show> page = EventService.isOwner(event, caller) ? this.shows.findByEventId(event.id(), stable)
				: this.shows.findByEventIdAndStatusAndStartsAtAfter(event.id(), Show.Status.PUBLISHED, Instant.now(),
						stable);
		Map<UUID, Venue> venues = new HashMap<>();
		this.venues.findAllById(page.map(Show::venueId).toSet()).forEach(venue -> venues.put(venue.id(), venue));
		return page.map(show -> ShowSummary.of(show, venues.get(show.venueId())));
	}

	/** A published Show for anyone, even once it has started; its owner also sees it as a draft. */
	@Transactional(readOnly = true)
	ShowDetail visible(UUID id, Optional<Caller> caller) {
		Show show = this.shows.findById(id).orElseThrow(ShowService::notFound);
		if (show.status() != Show.Status.PUBLISHED && !isOwner(show, caller)) {
			throw notFound();
		}
		return ShowDetail.of(show, venue(show));
	}

	private Show owned(UUID id, Caller organizer) {
		Show show = this.shows.findById(id).orElseThrow(ShowService::notFound);
		if (!isOwner(show, Optional.of(organizer))) {
			if (show.status() == Show.Status.PUBLISHED) {
				throw new AccessDeniedException("Only the Organizer who created a Show's Event can change it.");
			}
			throw notFound();
		}
		return show;
	}

	private boolean isOwner(Show show, Optional<Caller> caller) {
		return caller.isPresent() && EventService.isOwner(this.events.event(show.eventId()), caller);
	}

	private Venue approvedVenue(UUID venueId) {
		return this.venues.findById(venueId)
			.filter(venue -> venue.status() == Venue.Status.APPROVED)
			.orElseThrow(() -> new InvalidRequestException("venueId", "must be an approved Venue"));
	}

	private Venue venue(Show show) {
		return this.venues.findById(show.venueId()).orElseThrow();
	}

	private static Set<UUID> sectionIds(Venue venue) {
		Set<UUID> ids = new HashSet<>();
		venue.sections().forEach(section -> ids.add(section.id()));
		return ids;
	}

	private Show save(Show show) {
		try {
			return this.shows.saveAndFlush(show);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changedMeanwhile();
		}
	}

	private static ConflictException changedMeanwhile() {
		return new ConflictException("This Show was changed by someone else just now. Reload it and try again.");
	}

	private static NotFoundException notFound() {
		return new NotFoundException("No Show with that id.");
	}

}
