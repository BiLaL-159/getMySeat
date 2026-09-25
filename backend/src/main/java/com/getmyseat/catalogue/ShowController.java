package com.getmyseat.catalogue;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.ShowBrowseResponses.ShowDetail;
import com.getmyseat.catalogue.ShowBrowseResponses.ShowSummary;
import com.getmyseat.shared.api.InvalidRequestException;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@RestController
class ShowController {

	private static final SortAllowList SORTABLE = SortAllowList.of("startsAt", "createdAt");

	private final ShowService service;

	ShowController(ShowService service) {
		this.service = service;
	}

	/**
	 * @param venueId an approved Venue
	 * @param startsAt when the Show starts, in the future
	 * @param version when editing, the {@code version} of the Show you edited; a stale one is rejected with
	 * {@code 409}. Ignored when scheduling.
	 */
	record ShowRequest(@NotNull UUID venueId, @NotNull @Future Instant startsAt, @Nullable Long version) {
	}

	/** Every Section Price of the Show; Sections left out have no price. */
	record PricesRequest(@NotNull @Size(max = 500) List<@Valid @NotNull PriceRequest> prices) {
	}

	/**
	 * @param amountPaise whole paise, so {@code 50000} is ₹500
	 * @param currency only {@code INR} for now
	 */
	record PriceRequest(@NotNull UUID sectionId, @NotNull @Positive Long amountPaise,
			@NotNull @Pattern(regexp = SectionPrice.INR, message = "must be INR") String currency) {

		SectionPrice price() {
			return new SectionPrice(this.sectionId, this.amountPaise, this.currency);
		}

	}

	@PostMapping("/api/v1/events/{eventId}/shows")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Schedule a Show of one of your Events",
			description = "At an approved Venue and a future start time. It starts as a draft that only you can see.")
	ResponseEntity<ShowResponse> schedule(@PathVariable UUID eventId, Caller organizer,
			@Valid @RequestBody ShowRequest request) {
		ShowResponse show = this.service.schedule(eventId, organizer, request.venueId(), request.startsAt());
		return ResponseEntity.created(URI.create("/api/v1/shows/" + show.id())).body(show);
	}

	@PutMapping("/api/v1/shows/{id}")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Move one of your draft Shows to another start time or Venue",
			description = "Moving it to another Venue drops the prices of Sections the new Venue doesn't have. Send the version you last read; if the Show has changed since, you get 409.")
	ShowResponse reschedule(@PathVariable UUID id, Caller organizer, @Valid @RequestBody ShowRequest request) {
		Long version = request.version();
		if (version == null) {
			throw new InvalidRequestException("version", "must be the version of the Show you edited");
		}
		return this.service.reschedule(id, organizer, request.venueId(), request.startsAt(), version);
	}

	@PutMapping("/api/v1/shows/{id}/prices")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Replace every Section Price of one of your draft Shows",
			description = "Only Sections of the Show's Venue, each at most once. Every Section needs a price before the Show can be published.")
	ShowResponse reprice(@PathVariable UUID id, Caller organizer, @Valid @RequestBody PricesRequest request) {
		return this.service.reprice(id, organizer, request.prices().stream().map(PriceRequest::price).toList());
	}

	@PostMapping("/api/v1/shows/{id}/publish")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Publish one of your draft Shows",
			description = "Needs a published Event, an approved Venue, a future start time and a price for every Section. Once published, its Venue, start time and prices are locked.")
	ShowResponse publish(@PathVariable UUID id, Caller organizer) {
		return this.service.publish(id, organizer);
	}

	@GetMapping("/api/v1/events/{eventId}/shows")
	@SecurityRequirements
	@Operation(summary = "An Event's upcoming Shows, soonest first, with their Venue",
			description = "Public: published Shows that haven't started. Signed in as the Event's owner, you see all its Shows, drafts and past ones included.")
	Page<ShowSummary> ofEvent(@PathVariable UUID eventId, Optional<Caller> caller,
			@ParameterObject @PageableDefault(sort = "startsAt") Pageable pageable) {
		return this.service.ofEvent(eventId, caller, SORTABLE.check(pageable));
	}

	@GetMapping("/api/v1/shows/{id}")
	@SecurityRequirements
	@Operation(summary = "A Show with its Venue and each Section's price, capacity or Seats",
			description = "Public for published Shows. Signed in, you also see your own drafts, whose Sections may not have a price yet.")
	ShowDetail show(@PathVariable UUID id, Optional<Caller> caller) {
		return this.service.visible(id, caller);
	}

}
