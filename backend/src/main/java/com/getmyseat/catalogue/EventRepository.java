package com.getmyseat.catalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	Page<Event> findByOwnerSubject(UUID ownerSubject, Pageable pageable);

	/**
	 * What a Customer searches published Events by; every filter is optional.
	 * @param q part of the title or description, ignoring case
	 * @param city the whole city of a Show's Venue, ignoring case
	 * @param from the first date a Show may start on, at its Venue
	 * @param to the last date a Show may start on, at its Venue
	 */
	record Filters(@Nullable String q, @Nullable String city, Event.@Nullable Category category,
			@Nullable LocalDate from, @Nullable LocalDate to) {

		boolean concernShows() {
			return hasText(this.city) || this.from != null || this.to != null;
		}

	}

	/**
	 * Published Events matching the filters. The city and dates must all match one Show that is published and
	 * starts after {@code now}.
	 */
	static Specification<Event> published(Filters filters, Instant now) {
		Specification<Event> spec = (event, query, cb) -> cb.equal(event.get("status"), Event.Status.PUBLISHED);
		String q = filters.q();
		if (hasText(q)) {
			String pattern = "%" + likeEscaped(q.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((event, query, cb) -> cb.or(cb.like(cb.lower(event.get("title")), pattern, '\\'),
					cb.like(cb.lower(event.get("description")), pattern, '\\')));
		}
		Event.Category category = filters.category();
		if (category != null) {
			spec = spec.and((event, query, cb) -> cb.equal(event.get("category"), category));
		}
		if (filters.concernShows()) {
			spec = spec.and(withUpcomingShow(filters, now));
		}
		return spec;
	}

	private static Specification<Event> withUpcomingShow(Filters filters, Instant now) {
		return (event, query, cb) -> {
			Subquery<Integer> shows = query.subquery(Integer.class);
			Root<Show> show = shows.from(Show.class);
			Root<Venue> venue = shows.from(Venue.class);
			List<Predicate> where = new ArrayList<>(List.of(cb.equal(show.get("eventId"), event.get("id")),
					cb.equal(show.get("status"), Show.Status.PUBLISHED),
					cb.greaterThan(show.<Instant>get("startsAt"), now), cb.equal(venue.get("id"), show.get("venueId"))));
			String city = filters.city();
			if (hasText(city)) {
				where.add(cb.equal(cb.lower(venue.get("city")), city.strip().toLowerCase(Locale.ROOT)));
			}
			// PostgreSQL's timezone(zone, timestamptz) gives the wall-clock time at the Venue.
			Expression<LocalDateTime> localStart = cb.function("timezone", LocalDateTime.class, venue.get("timeZone"),
					show.get("startsAt"));
			LocalDate from = filters.from();
			if (from != null) {
				where.add(cb.greaterThanOrEqualTo(localStart, from.atStartOfDay()));
			}
			LocalDate to = filters.to();
			if (to != null) {
				where.add(cb.lessThan(localStart, to.plusDays(1).atStartOfDay()));
			}
			return cb.exists(shows.select(cb.literal(1)).where(where.toArray(Predicate[]::new)));
		};
	}

	private static boolean hasText(@Nullable String text) {
		return text != null && !text.isBlank();
	}

	private static String likeEscaped(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
