package com.getmyseat.access;

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

import com.getmyseat.access.OrganizerApplicationController.OrganizerApplicationResponse;
import com.getmyseat.shared.api.SortAllowList;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/organizer-applications")
@PreAuthorize("hasRole('ADMIN')")
class OrganizerApplicationAdminController {

	private static final SortAllowList SORTABLE = SortAllowList.of("createdAt");

	private final OrganizerApplicationService service;

	OrganizerApplicationAdminController(OrganizerApplicationService service) {
		this.service = service;
	}

	record RejectRequest(@NotBlank @Size(max = 1000) String reason) {
	}

	@GetMapping
	@Operation(summary = "The Organizer Application queue, oldest first",
			description = "Filter by status to see pending applications or look back at past decisions.")
	Page<OrganizerApplicationResponse> queue(@RequestParam(required = false) OrganizerApplication.@Nullable Status status,
			@ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
		SORTABLE.check(pageable);
		return this.service.queue(status, pageable).map(OrganizerApplicationResponse::of);
	}

	@PostMapping("/{id}/approve")
	@Operation(summary = "Approve an application and grant the ORGANIZER role",
			description = "The applicant gets the role on their next token refresh. Returns 503 and leaves the "
					+ "application pending if Keycloak can't be reached.")
	OrganizerApplicationResponse approve(@PathVariable UUID id, Caller admin) {
		return OrganizerApplicationResponse.of(this.service.approve(id, admin));
	}

	@PostMapping("/{id}/reject")
	@Operation(summary = "Reject an application with a reason")
	OrganizerApplicationResponse reject(@PathVariable UUID id, Caller admin, @Valid @RequestBody RejectRequest request) {
		return OrganizerApplicationResponse.of(this.service.reject(id, admin, request.reason().strip()));
	}

}
