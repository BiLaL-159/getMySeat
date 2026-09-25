package com.getmyseat.catalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
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
import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.NotFoundException;

/**
 * The Event lifecycle. Ownership is checked against the stored owner subject: someone else's draft is {@code 404}
 * and someone else's published Event is {@code 403} to change. Changes are optimistically locked on the Event's
 * version, so two Organizer sessions can't silently overwrite each other.
 */
@Service
class EventService {

	private static final Set<String> LANGUAGES = Set.of(Locale.getISOLanguages());

	private final EventRepository events;

	EventService(EventRepository events) {
		this.events = events;
	}

	@Transactional
	EventResponse create(Caller organizer, Event.Details details) {
		return EventResponse.of(this.events.saveAndFlush(new Event(organizer.subject(), checked(details), Instant.now())));
	}

	/**
	 * @param version the version the Organizer last saw
	 * @throws ConflictException if the Event has changed since
	 */
	@Transactional
	EventResponse change(UUID id, Caller organizer, Event.Details details, long version) {
		Event event = owned(id, organizer);
		if (event.version() != version) {
			throw changedMeanwhile();
		}
		event.change(checked(details));
		return EventResponse.of(save(event));
	}

	@Transactional
	EventResponse publish(UUID id, Caller organizer) {
		Event event = owned(id, organizer);
		event.publish(Instant.now());
		return EventResponse.of(save(event));
	}

	@Transactional(readOnly = true)
	Page<EventResponse> mine(Caller organizer, Pageable pageable) {
		return this.events.findByOwnerSubject(organizer.subject(), withTieBreaker(pageable)).map(EventResponse::of);
	}

	/** @throws InvalidRequestException if the date range ends before it starts */
	@Transactional(readOnly = true)
	Page<EventResponse> search(EventRepository.Filters filters, Pageable pageable) {
		LocalDate from = filters.from();
		LocalDate to = filters.to();
		if (from != null && to != null && to.isBefore(from)) {
			throw new InvalidRequestException("to", "must not be before from");
		}
		return this.events.findAll(EventRepository.published(filters, Instant.now()), withTieBreaker(pageable))
			.map(event -> EventResponse.of(event).withoutOwner());
	}

	/** A published Event for anyone; its owner also sees it as a draft. */
	@Transactional(readOnly = true)
	EventResponse visible(UUID id, Optional<Caller> caller) {
		Event event = visibleEvent(id, caller);
		return isOwner(event, caller) ? EventResponse.of(event) : EventResponse.of(event).withoutOwner();
	}

	/** @throws NotFoundException unless the Event is published or the caller owns it */
	Event visibleEvent(UUID id, Optional<Caller> caller) {
		Event event = event(id);
		if (event.status() != Event.Status.PUBLISHED && !isOwner(event, caller)) {
			throw notFound();
		}
		return event;
	}

	/** Any Event, whoever owns it; check visibility before showing it to anyone. */
	Event event(UUID id) {
		return this.events.findById(id).orElseThrow(EventService::notFound);
	}

	static boolean isOwner(Event event, Optional<Caller> caller) {
		return caller.filter(c -> event.isOwnedBy(c.subject())).isPresent();
	}

	/** @throws NotFoundException for someone else's draft; {@link AccessDeniedException} for their published Event */
	Event owned(UUID id, Caller organizer) {
		Event event = event(id);
		if (!event.isOwnedBy(organizer.subject())) {
			if (event.status() == Event.Status.PUBLISHED) {
				throw new AccessDeniedException("Only the Organizer who created an Event can change it.");
			}
			throw notFound();
		}
		return event;
	}

	private Event save(Event event) {
		try {
			return this.events.saveAndFlush(event);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changedMeanwhile();
		}
	}

	private static Event.Details checked(Event.Details details) {
		if (!LANGUAGES.contains(details.language())) {
			throw new InvalidRequestException("language", "must be an ISO 639-1 language code such as en or hi");
		}
		return details;
	}

	/** Breaks ties on id so pages stay stable when sort values repeat. */
	private static Pageable withTieBreaker(Pageable pageable) {
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort().and(Sort.by("id")));
	}

	private static ConflictException changedMeanwhile() {
		return new ConflictException("This Event was changed by someone else just now. Reload it and try again.");
	}

	private static NotFoundException notFound() {
		return new NotFoundException("No Event with that id.");
	}

}
