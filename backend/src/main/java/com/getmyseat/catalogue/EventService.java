package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
	Event create(Caller organizer, Event.Details details) {
		return this.events.saveAndFlush(new Event(organizer.subject(), checked(details), Instant.now()));
	}

	/**
	 * @param version the version the Organizer last saw
	 * @throws ConflictException if the Event has changed since
	 */
	@Transactional
	Event change(UUID id, Caller organizer, Event.Details details, long version) {
		Event event = owned(id, organizer);
		if (event.version() != version) {
			throw changedMeanwhile();
		}
		event.change(checked(details));
		return save(event);
	}

	@Transactional
	Event publish(UUID id, Caller organizer) {
		Event event = owned(id, organizer);
		event.publish(Instant.now());
		return save(event);
	}

	@Transactional(readOnly = true)
	Page<Event> mine(Caller organizer, Pageable pageable) {
		return this.events.findByOwnerSubject(organizer.subject(), withTieBreaker(pageable));
	}

	@Transactional(readOnly = true)
	Page<Event> search(@Nullable String q, Pageable pageable) {
		return this.events.findAll(EventRepository.published(q), withTieBreaker(pageable));
	}

	/** A published Event for anyone; its owner also sees it as a draft. */
	@Transactional(readOnly = true)
	Event visible(UUID id, Optional<Caller> caller) {
		Event event = this.events.findById(id).orElseThrow(EventService::notFound);
		if (event.status() == Event.Status.PUBLISHED || caller.filter(c -> event.isOwnedBy(c.subject())).isPresent()) {
			return event;
		}
		throw notFound();
	}

	private Event owned(UUID id, Caller organizer) {
		Event event = this.events.findById(id).orElseThrow(EventService::notFound);
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
