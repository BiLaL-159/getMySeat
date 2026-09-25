package com.getmyseat.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Stands in for the application's {@link Clock} in API tests: real time, moved on by however far a test
 * {@link #advance advances} it. Call {@link #reset()} before each test.
 */
public final class TestClock extends Clock {

	private volatile Duration offset = Duration.ZERO;

	@Override
	public Instant instant() {
		return Instant.now().plus(this.offset);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException("TestClock is always UTC");
	}

	public void advance(Duration duration) {
		this.offset = this.offset.plus(duration);
	}

	public void reset() {
		this.offset = Duration.ZERO;
	}

}
