package com.getmyseat.booking;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.booking.InventoryUnavailableException.UnavailableSection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
class HoldController {

	private final HoldService service;

	HoldController(HoldService service) {
		this.service = service;
	}

	/**
	 * Seats, General Admission places or both: 1 to 10 tickets in all.
	 * @param seats Seats of the Show's Seated Sections, each once
	 * @param generalAdmission places in the Show's General Admission Sections, each Section once
	 */
	record HoldRequest(@Nullable @Size(max = HoldService.MAX_TICKETS) List<@NotNull UUID> seats,
			@Nullable @Size(max = HoldService.MAX_TICKETS) List<@Valid @NotNull PlacesRequest> generalAdmission) {

		List<UUID> seatIds() {
			return (this.seats != null) ? this.seats : List.of();
		}

		List<HoldService.Places> places() {
			return (this.generalAdmission != null) ? this.generalAdmission.stream().map(PlacesRequest::places).toList()
					: List.of();
		}

	}

	/** @param quantity how many places, at least 1 */
	record PlacesRequest(@NotNull UUID sectionId, @NotNull @Min(1) Integer quantity) {

		HoldService.Places places() {
			return new HoldService.Places(this.sectionId, this.quantity);
		}

	}

	/** The {@code 409} body when a Hold can't be made; documentation only. */
	@Schema(name = "InventoryUnavailableProblem")
	record InventoryUnavailableProblem(
			@Schema(example = "urn:getmyseat:problem:inventory-unavailable") URI type, String title, int status,
			String detail,
			@Schema(description = "Seats that someone else holds; empty if a race with other Holds was the cause") List<UUID> unavailableSeats,
			@Schema(description = "General Admission Sections without enough places left, and how many are left") List<UnavailableSection> unavailableSections) {
	}

	@PostMapping("/api/v1/shows/{id}/holds")
	@PreAuthorize("hasRole('CUSTOMER')")
	@Operation(summary = "Hold Seats and General Admission places at a published Show",
			description = "All or nothing, 1 to 10 tickets. Prices are the Section Prices at the moment of the Hold, and the Hold expires after the Hold time, 10 minutes by default. If anything you asked for is gone, nothing is held and you get 409 naming what's unavailable. A draft or unknown Show is 404; a Show that has started is 409.")
	@ApiResponse(responseCode = "201", description = "Held")
	@ApiResponse(responseCode = "409",
			description = "Some of the inventory is unavailable (type urn:getmyseat:problem:inventory-unavailable), or the Show has started (type urn:getmyseat:problem:conflict)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = InventoryUnavailableProblem.class)))
	ResponseEntity<HoldResponse> create(@PathVariable UUID id, Caller customer,
			@Valid @RequestBody HoldRequest request) {
		HoldResponse hold;
		try {
			hold = this.service.create(id, customer, request.seatIds(), request.places());
		}
		catch (PessimisticLockingFailureException ex) {
			// A deadlock or serialization failure: the transaction rolled back, so it's safe to try again.
			throw InventoryUnavailableException.contended();
		}
		return ResponseEntity.created(URI.create("/api/v1/holds/" + hold.id())).body(hold);
	}

	@GetMapping("/api/v1/holds/{id}")
	@Operation(summary = "One of your Holds", description = "Anyone else's Hold is 404.")
	HoldResponse find(@PathVariable UUID id, Caller customer) {
		return this.service.find(id, customer);
	}

}
