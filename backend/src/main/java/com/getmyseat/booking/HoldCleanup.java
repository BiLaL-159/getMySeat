package com.getmyseat.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Expires the Holds that nobody reads, so abandoned checkouts give their inventory back. A Hold that is read after
 * its expiry time expires right then instead; availability reads don't expire Holds, so a Seat whose Hold has
 * expired stays unavailable until the next run at the latest.
 */
@Component
class HoldCleanup {

	/** How many Holds one transaction expires. */
	static final int BATCH_SIZE = 100;

	private static final Logger log = LoggerFactory.getLogger(HoldCleanup.class);

	private final HoldService service;

	HoldCleanup(HoldService service) {
		this.service = service;
	}

	/** Expires due Holds a batch at a time, until a batch comes back short. */
	@Scheduled(fixedDelayString = "${getmyseat.holds.cleanup-interval}")
	void expireDueHolds() {
		try {
			while (this.service.expireDue(BATCH_SIZE) == BATCH_SIZE) {
				// Another full batch may be waiting.
			}
		}
		catch (PessimisticLockingFailureException ex) {
			// A deadlock with a Hold being made or released: this batch rolled back and the next run picks it up.
			log.info("Hold cleanup gave way to another transaction; trying again next run", ex);
		}
	}

}
