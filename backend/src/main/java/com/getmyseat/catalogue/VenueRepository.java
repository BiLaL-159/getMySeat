package com.getmyseat.catalogue;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface VenueRepository extends JpaRepository<Venue, UUID>, JpaSpecificationExecutor<Venue> {

	/**
	 * Loads the Venue with a row lock, so changes, submissions and decisions on the same Venue happen one after
	 * another and each sees the status the previous one left.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Venue v where v.id = :id")
	Optional<Venue> findForUpdate(UUID id);

	Page<Venue> findByOwnerSubject(UUID ownerSubject, Pageable pageable);

	Page<Venue> findByStatus(Venue.Status status, Pageable pageable);

	/** Approved Venues whose name contains {@code q} and whose city is {@code city}, both ignoring case. */
	static Specification<Venue> approved(@Nullable String q, @Nullable String city) {
		Specification<Venue> spec = (venue, query, cb) -> cb.equal(venue.get("status"), Venue.Status.APPROVED);
		if (q != null && !q.isBlank()) {
			String pattern = "%" + likeEscaped(q.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((venue, query, cb) -> cb.like(cb.lower(venue.get("name")), pattern, '\\'));
		}
		if (city != null && !city.isBlank()) {
			String wanted = city.strip().toLowerCase(Locale.ROOT);
			spec = spec.and((venue, query, cb) -> cb.equal(cb.lower(venue.get("city")), wanted));
		}
		return spec;
	}

	private static String likeEscaped(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
