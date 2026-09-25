package com.getmyseat.booking;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface HoldRepository extends JpaRepository<Hold, UUID> {

	@Query("select h from Hold h where h.customerSubject = :customer and h.showId = :show and h.status = ACTIVE")
	Optional<Hold> findActive(UUID customer, UUID show);

	/**
	 * {@code ACTIVE → RELEASED}, only if the Hold is still active. Clears the persistence context, so re-read the Hold
	 * afterwards.
	 * @return 1 if this call released the Hold, 0 if it wasn't active
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Hold h set h.status = RELEASED, h.version = h.version + 1 where h.id = :id and h.status = ACTIVE")
	int release(UUID id);

}
