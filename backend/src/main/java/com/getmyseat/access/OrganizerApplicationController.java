package com.getmyseat.access;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/organizer-applications")
@PreAuthorize("hasRole('CUSTOMER')")
class OrganizerApplicationController {

	private final OrganizerApplicationService service;

	OrganizerApplicationController(OrganizerApplicationService service) {
		this.service = service;
	}

	record SubmitRequest(@NotBlank @Size(max = 200) String organisationName,
			@NotBlank @Pattern(regexp = "\\+?[0-9][0-9 ()-]{6,30}",
					message = "must be a phone number such as +91 98765 43210") String contactPhone,
			@NotBlank @Size(max = 2000) String description) {
	}

	record OrganizerApplicationResponse(UUID id, OrganizerApplication.Status status, UUID applicantSubject,
			String applicantName, @Nullable String applicantEmail, String organisationName, String contactPhone,
			String description, @Nullable String rejectionReason, Instant createdAt, @Nullable UUID decidedBy,
			@Nullable Instant decidedAt) {

		static OrganizerApplicationResponse of(OrganizerApplication application) {
			return new OrganizerApplicationResponse(application.id(), application.status(),
					application.applicantSubject(), application.applicantName(), application.applicantEmail(),
					application.organisationName(), application.contactPhone(), application.description(),
					application.rejectionReason(), application.createdAt(), application.decidedBy(),
					application.decidedAt());
		}

	}

	@PostMapping
	@Operation(summary = "Apply to become an Organizer",
			description = "Your name and email are taken from your token. You can have one pending application at a time.")
	ResponseEntity<OrganizerApplicationResponse> submit(Caller caller, @Valid @RequestBody SubmitRequest request) {
		OrganizerApplication application = this.service.submit(caller, request.organisationName().strip(),
				request.contactPhone().strip(), request.description().strip());
		return ResponseEntity.created(URI.create("/api/v1/organizer-applications/mine"))
			.body(OrganizerApplicationResponse.of(application));
	}

	@GetMapping("/mine")
	@Operation(summary = "Your latest Organizer Application")
	OrganizerApplicationResponse mine(Caller caller) {
		return OrganizerApplicationResponse.of(this.service.latestOf(caller));
	}

}
