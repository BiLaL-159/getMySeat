package com.getmyseat.catalogue;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.getmyseat.access.Caller;
import com.getmyseat.catalogue.VenueResponses.VenueResponse;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/venues")
@PreAuthorize("hasRole('ADMIN')")
class VenueAdminController {

	private static final SortAllowList SORTABLE = SortAllowList.of("submittedAt", "createdAt", "name");

	private final VenueService service;

	VenueAdminController(VenueService service) {
		this.service = service;
	}

	record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
	}

	@GetMapping
	@Operation(summary = "Venues to review, with their full layout",
			description = "Oldest submission first. Filter by status, such as PENDING_REVIEW.")
	Page<VenueResponse> queue(@RequestParam(required = false) Venue.@Nullable Status status,
			@ParameterObject @PageableDefault(sort = "submittedAt", direction = Sort.Direction.ASC) Pageable pageable) {
		return this.service.reviewQueue(status, SORTABLE.check(pageable));
	}

	@PostMapping("/{id}/approve")
	@Operation(summary = "Approve a Venue", description = "Its layout is then fixed and any Organizer can use it.")
	VenueResponse approve(@PathVariable UUID id, Caller admin) {
		return this.service.approve(id, admin);
	}

	@PostMapping("/{id}/reject")
	@Operation(summary = "Reject a Venue with a reason", description = "The Organizer can fix it and resubmit.")
	VenueResponse reject(@PathVariable UUID id, Caller admin, @Valid @RequestBody RejectRequest request) {
		return this.service.reject(id, admin, request.reason().strip());
	}

}
