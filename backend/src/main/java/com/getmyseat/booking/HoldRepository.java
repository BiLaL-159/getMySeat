package com.getmyseat.booking;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface HoldRepository extends JpaRepository<Hold, UUID> {

	@Query("select h from Hold h where h.customerSubject = :customer and h.showId = :show and h.status = ACTIVE")
	Optional<Hold> findActive(UUID customer, UUID show);

	/**
	 * {@code ACTIVE → RELEASED} or {@code ACTIVE → EXPIRED}, only if the Hold is still active. Clears the persistence
	 * context, so re-read the Hold afterwards.
	 * @return 1 if this call ended the Hold, 0 if it wasn't active
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Hold h set h.status = :outcome, h.version = h.version + 1 where h.id = :id and h.status = ACTIVE")
	int end(UUID id, Hold.Status outcome);

	/**
	 * Up to {@code limit} active Holds whose expiry time has come, soonest expired first, locked until the transaction
	 * ends. Skips Holds another transaction has locked, so concurrent runs never wait on each other or on a Customer.
	 */
	@Query(value = """
			SELECT * FROM hold WHERE status = 'ACTIVE' AND expires_at <= :now
			ORDER BY expires_at
			LIMIT :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<Hold> lockDue(Instant now, int limit);

	/**
	 * {@code ACTIVE → EXPIRED} for each of the Holds that is still active. Clears the persistence context.
	 * @return how many Holds this call expired
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Hold h set h.status = EXPIRED, h.version = h.version + 1 where h.id in :ids and h.status = ACTIVE")
	int expire(Collection<UUID> ids);

}
