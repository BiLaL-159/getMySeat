package com.getmyseat.catalogue;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/events")
class EventController {

	private static final SortAllowList MINE_SORTABLE = SortAllowList.of("title", "createdAt");

	private static final SortAllowList PUBLIC_SORTABLE = SortAllowList.of("title", "publishedAt");

	private final EventService service;

	EventController(EventService service) {
		this.service = service;
	}

	/** @param language an ISO 639-1 language code such as {@code en} or {@code hi} */
	record CreateRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 5000) String description,
			@NotNull Event.Category category,
			@NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO 639-1 language code such as en or hi") String language) {

		Event.Details details() {
			return EventController.details(this.title, this.description, this.category, this.language);
		}

	}

	/** @param version the {@code version} of the Event you edited; a stale one is rejected with {@code 409} */
	record ChangeRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 5000) String description,
			@NotNull Event.Category category,
			@NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO 639-1 language code such as en or hi") String language,
			@NotNull Long version) {

		Event.Details details() {
			return EventController.details(this.title, this.description, this.category, this.language);
		}

	}

	/**
	 * @param ownerSubject the Organizer who created the Event; only shown to them
	 * @param version pass it back when editing, so a concurrent edit is detected
	 */
	record EventResponse(UUID id, String title, String description, Event.Category category, String language,
			Event.Status status, @Nullable UUID ownerSubject, Instant createdAt, @Nullable Instant publishedAt,
			long version) {

		static EventResponse of(Event event) {
			return new EventResponse(event.id(), event.title(), event.description(), event.category(),
					event.language(), event.status(), event.ownerSubject(), event.createdAt(), event.publishedAt(),
					event.version());
		}

		/** Without the owner's Keycloak subject, for anyone other than the owner. */
		static EventResponse publicly(Event event) {
			return new EventResponse(event.id(), event.title(), event.description(), event.category(),
					event.language(), event.status(), null, event.createdAt(), event.publishedAt(), event.version());
		}

	}

	@PostMapping
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Create an Event", description = "It starts as a draft that only you can see.")
	ResponseEntity<EventResponse> create(Caller organizer, @Valid @RequestBody CreateRequest request) {
		Event event = this.service.create(organizer, request.details());
		return ResponseEntity.created(URI.create("/api/v1/events/" + event.id())).body(EventResponse.of(event));
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Edit one of your Events, draft or published",
			description = "Send the version you last read; if the Event has changed since, you get 409 and should reload it.")
	EventResponse change(@PathVariable UUID id, Caller organizer, @Valid @RequestBody ChangeRequest request) {
		return EventResponse.of(this.service.change(id, organizer, request.details(), request.version()));
	}

	@PostMapping("/{id}/publish")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Publish one of your draft Events", description = "Anyone can see it once it's published.")
	EventResponse publish(@PathVariable UUID id, Caller organizer) {
		return EventResponse.of(this.service.publish(id, organizer));
	}

	@GetMapping("/mine")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Your Events, drafts included, newest first")
	Page<EventResponse> mine(Caller organizer,
			@PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return this.service.mine(organizer, MINE_SORTABLE.check(pageable)).map(EventResponse::of);
	}

	@GetMapping
	@SecurityRequirements
	@Operation(summary = "Search published Events, most recently published first",
			description = "Public. q matches part of the title or description, ignoring case.")
	Page<EventResponse> search(@RequestParam(required = false) @Nullable String q,
			@PageableDefault(sort = "publishedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return this.service.search(q, PUBLIC_SORTABLE.check(pageable)).map(EventResponse::publicly);
	}

	@GetMapping("/{id}")
	@SecurityRequirements
	@Operation(summary = "An Event", description = "Public for published Events. Signed in, you also see your own drafts.")
	EventResponse event(@PathVariable UUID id, Optional<Caller> caller) {
		Event event = this.service.visible(id, caller);
		return caller.filter(c -> event.isOwnedBy(c.subject())).isPresent() ? EventResponse.of(event)
				: EventResponse.publicly(event);
	}

	private static Event.Details details(String title, String description, Event.Category category, String language) {
		return new Event.Details(title.strip(), description.strip(), category, language.toLowerCase(Locale.ROOT));
	}

}
