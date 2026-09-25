package com.getmyseat.access;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface OrganizerApplicationRepository extends JpaRepository<OrganizerApplication, UUID> {

	/** Loads the application with a row lock, so a concurrent decision waits and then sees it decided. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from OrganizerApplication a where a.id = :id")
	Optional<OrganizerApplication> findForDecision(UUID id);

	Page<OrganizerApplication> findByStatus(OrganizerApplication.Status status, Pageable pageable);

	boolean existsByApplicantSubjectAndStatus(UUID applicantSubject, OrganizerApplication.Status status);

	Optional<OrganizerApplication> findFirstByApplicantSubjectOrderByCreatedAtDesc(UUID applicantSubject);

}
