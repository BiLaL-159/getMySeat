package com.getmyseat.booking;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

@RestController
class AvailabilityController {

	private final AvailabilityService service;

	AvailabilityController(AvailabilityService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/shows/{id}/availability")
	@SecurityRequirements
	@Operation(summary = "What's left to sell at a published Show",
			description = "Public. For each Section, in layout order: every Seat of a Seated Section with whether it's available, or a General Admission Section's capacity and how many places are left. A draft or unknown Show is 404.")
	@ApiResponse(responseCode = "200", description = "The Show's availability")
	@ApiResponse(responseCode = "404", description = "No published Show with that id",
			content = @Content(mediaType = "application/problem+json"))
	ShowAvailability availability(@PathVariable UUID id) {
		return this.service.availability(id);
	}

}
