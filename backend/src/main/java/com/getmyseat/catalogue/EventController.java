package com.getmyseat.catalogue;

import java.net.URI;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
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
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

	/**
	 * @param language an ISO 639-1 language code such as {@code en} or {@code hi}
	 * @param version when editing, the {@code version} of the Event you edited; a stale one is rejected with
	 * {@code 409}. Ignored when creating.
	 */
	record EventRequest(@NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 5000) String description,
			@NotNull Event.Category category, @NotBlank String language, @Nullable Long version) {

		Event.Details details() {
			return new Event.Details(this.title.strip(), this.description.strip(), this.category,
					this.language.strip().toLowerCase(Locale.ROOT));
		}

	}

	@PostMapping
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Create an Event", description = "It starts as a draft that only you can see.")
	ResponseEntity<EventResponse> create(Caller organizer, @Valid @RequestBody EventRequest request) {
		EventResponse event = this.service.create(organizer, request.details());
		return ResponseEntity.created(URI.create("/api/v1/events/" + event.id())).body(event);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Edit one of your Events, draft or published",
			description = "Send the version you last read; if the Event has changed since, you get 409 and should reload it.")
	EventResponse change(@PathVariable UUID id, Caller organizer, @Valid @RequestBody EventRequest request) {
		Long version = request.version();
		if (version == null) {
			throw new InvalidRequestException("version", "must be the version of the Event you edited");
		}
		return this.service.change(id, organizer, request.details(), version);
	}

	@PostMapping("/{id}/publish")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Publish one of your draft Events", description = "Anyone can see it once it's published.")
	EventResponse publish(@PathVariable UUID id, Caller organizer) {
		return this.service.publish(id, organizer);
	}

	@GetMapping("/mine")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Your Events, drafts included, newest first")
	Page<EventResponse> mine(Caller organizer,
			@ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return this.service.mine(organizer, MINE_SORTABLE.check(pageable));
	}

	@GetMapping
	@SecurityRequirements
	@Operation(summary = "Search published Events, most recently published first",
			description = "Public. q matches part of the title or description, ignoring case. city, from and to match Events with at least one upcoming published Show in that city (ignoring case) starting between those dates, inclusive, in the Venue's time zone.")
	Page<EventResponse> search(@RequestParam(required = false) @Nullable String q,
			@RequestParam(required = false) @Nullable String city,
			@RequestParam(required = false) Event.@Nullable Category category,
			@Parameter(description = "ISO date such as 2026-10-01") @RequestParam(required = false) @Nullable LocalDate from,
			@Parameter(description = "ISO date such as 2026-10-31") @RequestParam(required = false) @Nullable LocalDate to,
			@ParameterObject @PageableDefault(sort = "publishedAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return this.service.search(new EventRepository.Filters(q, city, category, from, to),
				PUBLIC_SORTABLE.check(pageable));
	}

	@GetMapping("/{id}")
	@SecurityRequirements
	@Operation(summary = "An Event", description = "Public for published Events. Signed in, you also see your own drafts.")
	EventResponse event(@PathVariable UUID id, Optional<Caller> caller) {
		return this.service.visible(id, caller);
	}

}
