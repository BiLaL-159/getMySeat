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
import com.getmyseat.shared.api.ConflictException;

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
			description = "All or nothing, 1 to 10 tickets. Prices are the Section Prices at the moment of the Hold, and the Hold expires after the Hold time, 10 minutes by default. You have at most one active Hold per Show: a new one releases your previous Hold for the Show, so it can include the same Seats. If anything you asked for is gone, nothing is held, your previous Hold stays active, and you get 409 naming what's unavailable. A draft or unknown Show is 404; a Show that has started is 409.")
	@ApiResponse(responseCode = "201", description = "Held")
	@ApiResponse(responseCode = "409",
			description = "Some of the inventory is unavailable (type urn:getmyseat:problem:inventory-unavailable), or the Show has started or you were making another Hold for it at the same moment (type urn:getmyseat:problem:conflict)",
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

	@PostMapping("/api/v1/holds/{id}/release")
	@Operation(summary = "Release one of your Holds early",
			description = "Its Seats and General Admission places become available again. Anyone else's Hold is 404; a Hold that isn't active is 409.")
	@ApiResponse(responseCode = "200", description = "Released")
	@ApiResponse(responseCode = "404", description = "No Hold of yours with that id",
			content = @Content(mediaType = "application/problem+json"))
	@ApiResponse(responseCode = "409",
			description = "The Hold isn't active, or other Holds were in flight for the same inventory (try again)",
			content = @Content(mediaType = "application/problem+json"))
	HoldResponse release(@PathVariable UUID id, Caller customer) {
		try {
			return this.service.release(id, customer);
		}
		catch (PessimisticLockingFailureException ex) {
			// A deadlock or serialization failure: the transaction rolled back, so it's safe to try again.
			throw new ConflictException(
					"Other Holds were in flight for the same inventory, so nothing was released. Try again.");
		}
	}

	@GetMapping("/api/v1/shows/{id}/holds/mine")
	@Operation(summary = "Your active Hold for a Show", description = "404 if you have none.")
	@ApiResponse(responseCode = "200", description = "Your active Hold")
	@ApiResponse(responseCode = "404", description = "You have no active Hold for the Show",
			content = @Content(mediaType = "application/problem+json"))
	HoldResponse mine(@PathVariable UUID id, Caller customer) {
		return this.service.mine(id, customer);
	}

}
