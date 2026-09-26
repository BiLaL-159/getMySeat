package com.getmyseat.booking;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface HoldIdempotencyKeyRepository extends JpaRepository<HoldIdempotencyKey, HoldIdempotencyKey.Id> {

	/** @return how many keys were deleted */
	@Modifying
	@Query("delete from HoldIdempotencyKey k where k.createdAt < :cutoff")
	int deleteCreatedBefore(Instant cutoff);

}
