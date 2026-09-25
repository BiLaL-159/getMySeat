package com.getmyseat.catalogue;

import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	Page<Event> findByOwnerSubject(UUID ownerSubject, Pageable pageable);

	/** Published Events whose title or description contains {@code q}, ignoring case. */
	static Specification<Event> published(@Nullable String q) {
		Specification<Event> spec = (event, query, cb) -> cb.equal(event.get("status"), Event.Status.PUBLISHED);
		if (q != null && !q.isBlank()) {
			String pattern = "%" + likeEscaped(q.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((event, query, cb) -> cb.or(cb.like(cb.lower(event.get("title")), pattern, '\\'),
					cb.like(cb.lower(event.get("description")), pattern, '\\')));
		}
		return spec;
	}

	private static String likeEscaped(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
