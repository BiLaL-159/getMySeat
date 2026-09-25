package com.getmyseat.catalogue;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.VenueResponses.VenueResponse;
import com.getmyseat.catalogue.VenueResponses.VenueSummary;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/venues")
class VenueController {

	private static final SortAllowList SORTABLE = SortAllowList.of("name", "city", "createdAt");

	private final VenueService service;

	VenueController(VenueService service) {
		this.service = service;
	}

	/** @param timeZone the Venue's IANA time zone, such as {@code Asia/Kolkata}, so clients can show local times */
	record VenueRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 500) String address,
			@NotBlank @Size(max = 100) String city, @NotBlank @Size(max = 64) String timeZone) {

		Venue.Details details() {
			return new Venue.Details(this.name.strip(), this.address.strip(), this.city.strip(), this.timeZone.strip());
		}

	}

	@PostMapping
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Propose a Venue", description = "It starts as a draft. Add Sections, then submit it for review.")
	ResponseEntity<VenueResponse> create(Caller organizer, @Valid @RequestBody VenueRequest request) {
		VenueResponse venue = this.service.create(organizer, request.details());
		return ResponseEntity.created(URI.create("/api/v1/venues/" + venue.id())).body(venue);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Change a draft or rejected Venue's details")
	VenueResponse change(@PathVariable UUID id, Caller organizer, @Valid @RequestBody VenueRequest request) {
		return this.service.change(id, organizer, request.details());
	}

	@PostMapping("/{id}/submit")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "Submit a Venue for Admin review",
			description = "Needs at least one Section, and Seats in every Seated Section. A rejected Venue can be resubmitted.")
	VenueResponse submit(@PathVariable UUID id, Caller organizer) {
		return this.service.submit(id, organizer);
	}

	@GetMapping("/mine")
	@PreAuthorize("hasRole('ORGANIZER')")
	@Operation(summary = "The Venues you proposed, in any status")
	Page<VenueSummary> mine(Caller organizer, @PageableDefault(sort = "createdAt") Pageable pageable) {
		return this.service.mine(organizer, SORTABLE.check(pageable));
	}

	@GetMapping
	@SecurityRequirements
	@Operation(summary = "Search approved Venues",
			description = "Public. q matches part of the name and city matches the whole city, both ignoring case.")
	Page<VenueSummary> search(@RequestParam(required = false) @Nullable String q,
			@RequestParam(required = false) @Nullable String city, @PageableDefault(sort = "name") Pageable pageable) {
		return this.service.search(q, city, SORTABLE.check(pageable));
	}

	@GetMapping("/{id}")
	@SecurityRequirements
	@Operation(summary = "A Venue with its Sections and Seats",
			description = "Public for approved Venues. Signed in, you also see your own Venues in any status.")
	VenueResponse venue(@PathVariable UUID id, Optional<Caller> caller) {
		return this.service.visible(id, caller);
	}

}
