package com.getmyseat.catalogue;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.VenueResponses.SectionResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A draft or rejected Venue's Sections and Seats, changed by the Organizer who proposed it. */
@RestController
@RequestMapping("/api/v1/venues/{venueId}/sections")
@PreAuthorize("hasRole('ORGANIZER')")
class VenueSectionController {

	private final VenueService service;

	VenueSectionController(VenueService service) {
		this.service = service;
	}

	/**
	 * @param capacity required for General Admission, not allowed for Seated
	 * @param rows the Seats of a Seated Section; more can be added later
	 */
	record NewSectionRequest(@NotBlank @Size(max = 100) String name, @NotNull Section.Kind kind,
			@Nullable Integer capacity, @Nullable @Size(max = 100) List<SeatRow> rows) {
	}

	record SectionRequest(@NotBlank @Size(max = 100) String name, @Nullable Integer capacity) {
	}

	record SeatsRequest(@NotEmpty @Size(max = 100) List<SeatRow> rows) {
	}

	@PostMapping
	@Operation(summary = "Add a Section",
			description = "Seated Sections take rows of Seats: a row label and either a seatCount (Seats 1 to n) or "
					+ "a list of seatNumbers. General Admission Sections take a capacity instead.")
	ResponseEntity<SectionResponse> add(@PathVariable UUID venueId, Caller organizer,
			@Valid @RequestBody NewSectionRequest request) {
		SectionResponse section = this.service.addSection(venueId, organizer, request.name().strip(),
				request.kind(), request.capacity(), (request.rows() != null) ? request.rows() : List.of());
		return ResponseEntity.created(URI.create("/api/v1/venues/" + venueId + "/sections/" + section.id()))
			.body(section);
	}

	@PutMapping("/{sectionId}")
	@Operation(summary = "Rename a Section or change its capacity", description = "A Section's kind can't change.")
	SectionResponse change(@PathVariable UUID venueId, @PathVariable UUID sectionId, Caller organizer,
			@Valid @RequestBody SectionRequest request) {
		return this.service.changeSection(venueId, sectionId, organizer, request.name().strip(), request.capacity());
	}

	@DeleteMapping("/{sectionId}")
	@Operation(summary = "Remove a Section and its Seats")
	ResponseEntity<Void> remove(@PathVariable UUID venueId, @PathVariable UUID sectionId, Caller organizer) {
		this.service.removeSection(venueId, sectionId, organizer);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{sectionId}/seats")
	@Operation(summary = "Add rows of Seats to a Seated Section")
	SectionResponse addSeats(@PathVariable UUID venueId, @PathVariable UUID sectionId, Caller organizer,
			@Valid @RequestBody SeatsRequest request) {
		return this.service.addSeats(venueId, sectionId, organizer, request.rows());
	}

	@DeleteMapping("/{sectionId}/seats/{seatId}")
	@Operation(summary = "Remove a Seat")
	ResponseEntity<Void> removeSeat(@PathVariable UUID venueId, @PathVariable UUID sectionId,
			@PathVariable UUID seatId, Caller organizer) {
		this.service.removeSeat(venueId, sectionId, seatId, organizer);
		return ResponseEntity.noContent().build();
	}

}
