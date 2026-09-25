package com.getmyseat.access;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.ConflictException;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A Customer's request to become an Organizer. {@code PENDING → APPROVED | REJECTED}; every decision records the
 * deciding Admin and when it was made.
 */
@Entity
@Table(name = "organizer_application")
class OrganizerApplication {

	enum Status {

		PENDING, APPROVED, REJECTED

	}

	@Id
	private UUID id;

	private UUID applicantSubject;

	private String applicantName;

	private @Nullable String applicantEmail;

	private String organisationName;

	private String contactPhone;

	private String description;

	@Enumerated(EnumType.STRING)
	private Status status;

	private @Nullable String rejectionReason;

	private @Nullable UUID decidedBy;

	private @Nullable Instant decidedAt;

	private Instant createdAt;

	@Version
	private @Nullable Long version;

	protected OrganizerApplication() {
	}

	OrganizerApplication(Caller applicant, String organisationName, String contactPhone, String description,
			Instant now) {
		this.id = UUID.randomUUID();
		this.applicantSubject = applicant.subject();
		this.applicantName = applicant.name();
		this.applicantEmail = applicant.email();
		this.organisationName = organisationName;
		this.contactPhone = contactPhone;
		this.description = description;
		this.status = Status.PENDING;
		this.createdAt = now;
	}

	void approve(UUID admin, Instant now) {
		requirePending();
		this.status = Status.APPROVED;
		this.decidedBy = admin;
		this.decidedAt = now;
	}

	void reject(UUID admin, String reason, Instant now) {
		requirePending();
		this.status = Status.REJECTED;
		this.rejectionReason = reason;
		this.decidedBy = admin;
		this.decidedAt = now;
	}

	/** @throws ConflictException if the application has already been decided */
	void requirePending() {
		if (this.status != Status.PENDING) {
			throw new ConflictException("This application has already been " + this.status.name().toLowerCase() + ".");
		}
	}

	UUID id() {
		return this.id;
	}

	UUID applicantSubject() {
		return this.applicantSubject;
	}

	String applicantName() {
		return this.applicantName;
	}

	@Nullable String applicantEmail() {
		return this.applicantEmail;
	}

	String organisationName() {
		return this.organisationName;
	}

	String contactPhone() {
		return this.contactPhone;
	}

	String description() {
		return this.description;
	}

	Status status() {
		return this.status;
	}

	@Nullable String rejectionReason() {
		return this.rejectionReason;
	}

	@Nullable UUID decidedBy() {
		return this.decidedBy;
	}

	@Nullable Instant decidedAt() {
		return this.decidedAt;
	}

	Instant createdAt() {
		return this.createdAt;
	}

}
