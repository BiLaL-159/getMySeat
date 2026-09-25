package com.getmyseat.catalogue;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ShowRepository extends JpaRepository<Show, UUID> {

	Page<Show> findByEventId(UUID eventId, Pageable pageable);

	Page<Show> findByEventIdAndStatusAndStartsAtAfter(UUID eventId, Show.Status status, Instant after,
			Pageable pageable);

}
