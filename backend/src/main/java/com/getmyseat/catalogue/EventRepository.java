package com.getmyseat.catalogue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	/**
	 * The sort property for when each Event's next matching Show starts, the Show a search result gives as its
	 * {@code nextShow}. Events without one come last either way.
	 */
	String NEXT_SHOW = "nextShow";

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
			return cityKey() != null || this.from != null || this.to != null;
		}

		/** The city as Venue cities are compared with it, lower case; {@code null} if there's no city filter. */
		@Nullable String cityKey() {
			return hasText(this.city) ? this.city.strip().toLowerCase(Locale.ROOT) : null;
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

	/**
	 * Orders by {@code sort}, whose properties are the Event's own or {@link #NEXT_SHOW}, then by id so pages stay
	 * stable when sort values repeat. Pass the page request unsorted, or its sort replaces this one.
	 */
	static Specification<Event> sortedBy(Sort sort, Filters filters, Instant now) {
		return (event, query, cb) -> {
			// Spring Data counts the matches with this specification too, and a count has no order.
			if (!Long.class.equals(query.getResultType())) {
				List<Order> orders = new ArrayList<>();
				for (Sort.Order order : sort.and(Sort.by("id"))) {
					orders.add(NEXT_SHOW.equals(order.getProperty())
							? orderedNullsLast(nextShowStart(filters, now, event, query, cb), order.isAscending(), cb)
							: order.isAscending() ? cb.asc(event.get(order.getProperty()))
									: cb.desc(event.get(order.getProperty())));
				}
				query.orderBy(orders);
			}
			return cb.conjunction();
		};
	}

	private static Specification<Event> withUpcomingShow(Filters filters, Instant now) {
		return (event, query, cb) -> cb
			.exists(matchingShows(Integer.class, (show) -> cb.literal(1), filters, now, event, query, cb));
	}

	/** When the Event's next matching Show starts; {@code null} if it has none. */
	private static Expression<Instant> nextShowStart(Filters filters, Instant now, Root<Event> event,
			CommonAbstractCriteria query, CriteriaBuilder cb) {
		return matchingShows(Instant.class, (show) -> cb.least(show.<Instant>get("startsAt")), filters, now, event,
				query, cb);
	}

	/**
	 * The Event's Shows that are published, start after {@code now}, and match the filters' city and dates, as
	 * {@code selection} of each.
	 */
	private static <T> Subquery<T> matchingShows(Class<T> type, Function<Root<Show>, Expression<T>> selection,
			Filters filters, Instant now, Root<Event> event, CommonAbstractCriteria query, CriteriaBuilder cb) {
		Subquery<T> shows = query.subquery(type);
		Root<Show> show = shows.from(Show.class);
		Root<Venue> venue = shows.from(Venue.class);
		List<Predicate> where = new ArrayList<>(List.of(cb.equal(show.get("eventId"), event.get("id")),
				cb.equal(show.get("status"), Show.Status.PUBLISHED), cb.greaterThan(show.<Instant>get("startsAt"), now),
				cb.equal(venue.get("id"), show.get("venueId"))));
		String city = filters.cityKey();
		if (city != null) {
			where.add(cb.equal(cb.lower(venue.get("city")), city));
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
		return shows.select(selection.apply(show)).where(where.toArray(Predicate[]::new));
	}

	private static Order orderedNullsLast(Expression<?> key, boolean ascending, CriteriaBuilder cb) {
		return ascending ? cb.asc(key, Nulls.LAST) : cb.desc(key, Nulls.LAST);
	}

	private static boolean hasText(@Nullable String text) {
		return text != null && !text.isBlank();
	}

	private static String likeEscaped(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
