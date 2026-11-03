package com.songrush.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

// A clock that only moves when the test (or ManualScheduler) moves it.
final class MutableClock extends Clock {

	private Instant now = Instant.parse("2026-11-03T00:00:00Z");

	void advance(Duration duration) {
		now = now.plus(duration);
	}

	void set(Instant instant) {
		now = instant;
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
