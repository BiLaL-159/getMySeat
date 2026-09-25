package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;
import org.jspecify.annotations.Nullable;

import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.NotFoundException;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A place where Shows happen, with its Section layout. An Organizer drafts it and submits it for review; an Admin
 * approves or rejects it. {@code DRAFT → PENDING_REVIEW → APPROVED | REJECTED}, and {@code REJECTED →
 * PENDING_REVIEW} on resubmit. The layout can change only in {@code DRAFT} or {@code REJECTED}; once
 * {@code APPROVED} it is fixed and any Organizer can use the Venue.
 */
@Entity
@Table(name = "venue")
class Venue {

	enum Status {

		DRAFT, PENDING_REVIEW, APPROVED, REJECTED

	}

	@Id
	private UUID id;

	private UUID ownerSubject;

	private String name;

	private String address;

	private String city;

	private String timeZone;

	@Enumerated(EnumType.STRING)
	private Status status;

	private @Nullable String rejectionReason;

	private @Nullable UUID decidedBy;

	private @Nullable Instant decidedAt;

	private @Nullable Instant submittedAt;

	private Instant createdAt;

	@Version
	private @Nullable Long version;

	@OneToMany(mappedBy = "venue", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("position")
	@BatchSize(size = 50)
	private List<Section> sections = new ArrayList<>();

	protected Venue() {
	}

	Venue(UUID ownerSubject, Details details, Instant now) {
		this.id = UUID.randomUUID();
		this.ownerSubject = ownerSubject;
		this.status = Status.DRAFT;
		this.createdAt = now;
		change(details);
	}

	/** The descriptive fields an Organizer sets. */
	record Details(String name, String address, String city, String timeZone) {
	}

	boolean isOwnedBy(UUID subject) {
		return this.ownerSubject.equals(subject);
	}

	void change(Details details) {
		this.name = details.name();
		this.address = details.address();
		this.city = details.city();
		this.timeZone = details.timeZone();
	}

	Section addSection(String name, Section.Kind kind, @Nullable Integer capacity) {
		requireUniqueSectionName(name, null);
		int position = this.sections.stream().mapToInt(Section::position).max().orElse(-1) + 1;
		Section section = new Section(this, name, kind, capacity, position);
		this.sections.add(section);
		return section;
	}

	Section renameSection(UUID sectionId, String name) {
		Section section = section(sectionId);
		requireUniqueSectionName(name, sectionId);
		section.rename(name);
		return section;
	}

	void removeSection(UUID sectionId) {
		this.sections.remove(section(sectionId));
	}

	Section section(UUID sectionId) {
		return this.sections.stream()
			.filter(section -> section.id().equals(sectionId))
			.findFirst()
			.orElseThrow(() -> new NotFoundException("No Section with that id in this Venue."));
	}

	/** Sends the Venue to an Admin; its layout must be complete. */
	void submit(Instant now) {
		if (this.status != Status.DRAFT && this.status != Status.REJECTED) {
			throw new ConflictException("Only a draft or rejected Venue can be submitted; this one is "
					+ this.status + ".");
		}
		if (this.sections.isEmpty()) {
			throw new ConflictException("Add at least one Section before submitting the Venue.");
		}
		for (Section section : this.sections) {
			if (section.kind() == Section.Kind.SEATED && section.seats().isEmpty()) {
				throw new ConflictException("Seated Section " + section.name() + " has no Seats yet.");
			}
		}
		this.status = Status.PENDING_REVIEW;
		this.submittedAt = now;
		this.rejectionReason = null;
		this.decidedBy = null;
		this.decidedAt = null;
	}

	void approve(UUID admin, Instant now) {
		requirePendingReview();
		this.status = Status.APPROVED;
		this.decidedBy = admin;
		this.decidedAt = now;
	}

	void reject(UUID admin, String reason, Instant now) {
		requirePendingReview();
		this.status = Status.REJECTED;
		this.rejectionReason = reason;
		this.decidedBy = admin;
		this.decidedAt = now;
	}

	/** @throws ConflictException unless the layout may still change */
	void requireEditable() {
		if (this.status == Status.APPROVED) {
			throw new ConflictException("This Venue is approved, so its layout can't change any more.");
		}
		if (this.status == Status.PENDING_REVIEW) {
			throw new ConflictException("This Venue is waiting for review, so it can't change until it's decided.");
		}
	}

	private void requirePendingReview() {
		if (this.status != Status.PENDING_REVIEW) {
			throw new ConflictException("Only a Venue waiting for review can be decided; this one is " + this.status
					+ ".");
		}
	}

	private void requireUniqueSectionName(String name, @Nullable UUID except) {
		for (Section section : this.sections) {
			if (section.name().equalsIgnoreCase(name) && !section.id().equals(except)) {
				throw new ConflictException("This Venue already has a Section named " + section.name() + ".");
			}
		}
	}

	UUID id() {
		return this.id;
	}

	UUID ownerSubject() {
		return this.ownerSubject;
	}

	String name() {
		return this.name;
	}

	String address() {
		return this.address;
	}

	String city() {
		return this.city;
	}

	String timeZone() {
		return this.timeZone;
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

	@Nullable Instant submittedAt() {
		return this.submittedAt;
	}

	Instant createdAt() {
		return this.createdAt;
	}

	List<Section> sections() {
		return List.copyOf(this.sections);
	}

}
