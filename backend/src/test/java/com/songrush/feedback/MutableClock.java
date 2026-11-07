package com.songrush.feedback;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// A clock that only moves when the test moves it. Shared by the feedback tests.
final class MutableClock extends Clock {

	private volatile Instant now = Instant.parse("2026-11-07T00:00:00Z");

	void advance(Duration duration) {
		now = now.plus(duration);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}

}
