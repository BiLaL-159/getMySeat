package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ShowRepository extends JpaRepository<Show, UUID> {

	Page<Show> findByEventId(UUID eventId, Pageable pageable);

	Page<Show> findByEventIdAndStatusAndStartsAtAfter(UUID eventId, Show.Status status, Instant after,
			Pageable pageable);

	/**
	 * The cities of Venues with a published Show starting after {@code now}, once each ignoring case, sorted ignoring
	 * case.
	 */
	@Query("""
			select min(v.city) from Show s, Venue v, Event e
			where v.id = s.venueId and e.id = s.eventId and s.status = :published and e.status = :eventPublished
			and s.startsAt > :now
			group by lower(v.city) order by lower(v.city)""")
	List<String> citiesWithShowsAfter(Instant now, Show.Status published, Event.Status eventPublished);

}
