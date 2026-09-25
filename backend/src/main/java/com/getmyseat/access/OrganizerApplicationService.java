package com.getmyseat.access;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.getmyseat.shared.api.ConflictException;
import com.getmyseat.shared.api.NotFoundException;
import com.getmyseat.shared.api.UpstreamUnavailableException;

@Service
class OrganizerApplicationService {

	private static final String ONE_PENDING_INDEX = "organizer_application_one_pending";

	private final OrganizerApplicationRepository applications;

	private final RoleGrants roleGrants;

	OrganizerApplicationService(OrganizerApplicationRepository applications, RoleGrants roleGrants) {
		this.applications = applications;
		this.roleGrants = roleGrants;
	}

	/** Not transactional: the insert runs in its own transaction so a unique-index clash can be turned into a 409. */
	OrganizerApplication submit(Caller applicant, String organisationName, String contactPhone, String description) {
		// An approved applicant's token lacks ORGANIZER until it's refreshed, so check their applications too.
		if (applicant.hasRole(Role.ORGANIZER) || this.applications
			.existsByApplicantSubjectAndStatus(applicant.subject(), OrganizerApplication.Status.APPROVED)) {
			throw new ConflictException("You're already an Organizer. Refresh your token if you don't see the role yet.");
		}
		if (this.applications.existsByApplicantSubjectAndStatus(applicant.subject(),
				OrganizerApplication.Status.PENDING)) {
			throw alreadyPending();
		}
		try {
			return this.applications.saveAndFlush(
					new OrganizerApplication(applicant, organisationName, contactPhone, description, Instant.now()));
		}
		catch (DataIntegrityViolationException ex) {
			// Two submissions raced past the check above; the partial unique index let only one through.
			if (violates(ex, ONE_PENDING_INDEX)) {
				throw alreadyPending();
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	OrganizerApplication latestOf(Caller applicant) {
		return this.applications.findFirstByApplicantSubjectOrderByCreatedAtDesc(applicant.subject())
			.orElseThrow(() -> new NotFoundException("You haven't applied to become an Organizer."));
	}

	@Transactional(readOnly = true)
	Page<OrganizerApplication> queue(OrganizerApplication.@Nullable Status status, Pageable pageable) {
		// Break ties on id so pages stay stable when applications share a timestamp.
		Pageable stable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
				pageable.getSort().and(Sort.by("id")));
		return (status != null) ? this.applications.findByStatus(status, stable) : this.applications.findAll(stable);
	}

	/**
	 * Locks the application, grants {@code ORGANIZER} in Keycloak, then marks it approved in the same transaction.
	 * If the grant fails the transaction rolls back and the application stays pending; the grant is idempotent,
	 * so the Admin can simply retry.
	 */
	@Transactional
	OrganizerApplication approve(UUID id, Caller admin) {
		OrganizerApplication application = lockForDecision(id);
		application.requirePending();
		try {
			this.roleGrants.grant(application.applicantSubject(), Role.ORGANIZER);
		}
		catch (RoleGrantException ex) {
			if (ex.reason() == RoleGrantException.Reason.UNKNOWN_USER) {
				throw new ConflictException(
						"The applicant's account no longer exists in Keycloak, so they can't be made an Organizer. "
								+ "Reject the application instead.");
			}
			throw new UpstreamUnavailableException(
					"Couldn't grant the Organizer role in Keycloak. The application is still pending; try again.", ex);
		}
		application.approve(admin.subject(), Instant.now());
		return save(application);
	}

	@Transactional
	OrganizerApplication reject(UUID id, Caller admin, String reason) {
		OrganizerApplication application = lockForDecision(id);
		application.reject(admin.subject(), reason, Instant.now());
		return save(application);
	}

	private OrganizerApplication lockForDecision(UUID id) {
		return this.applications.findForDecision(id)
			.orElseThrow(() -> new NotFoundException("No Organizer Application with that id."));
	}

	private OrganizerApplication save(OrganizerApplication application) {
		try {
			return this.applications.saveAndFlush(application);
		}
		catch (OptimisticLockingFailureException ex) {
			throw new ConflictException("This application was decided by someone else just now.");
		}
	}

	private static boolean violates(DataIntegrityViolationException ex, String constraint) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return constraint.equals(violation.getConstraintName());
			}
		}
		return false;
	}

	private static ConflictException alreadyPending() {
		return new ConflictException("You already have a pending Organizer Application.");
	}

}
