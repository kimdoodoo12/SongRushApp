package com.songrush.feedback;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

// Sliding windows over counted posts (cycle 082): at most 3 per address per 10 minutes, checked first,
// and at most 30 per hour in total. A timestamp t has expired once t + window <= now.
// Only an allowed post is recorded, so a refused one never consumes quota.
final class FeedbackRateLimiter {

	static final int PER_ADDRESS = 3;

	static final Duration ADDRESS_WINDOW = Duration.ofMinutes(10);

	static final int GLOBAL = 30;

	static final Duration GLOBAL_WINDOW = Duration.ofHours(1);

	private final Map<String, Deque<Instant>> byAddress = new HashMap<>();

	private final Deque<Instant> global = new ArrayDeque<>();

	// Records the post and returns empty when it is allowed; otherwise returns the Retry-After seconds:
	// the time until the oldest timestamp in the violated window expires, rounded up, at least 1.
	synchronized OptionalLong tryAcquire(String address, Instant now) {
		expire(global, GLOBAL_WINDOW, now);
		byAddress.values().removeIf(times -> {
			expire(times, ADDRESS_WINDOW, now);
			return times.isEmpty();
		});
		Deque<Instant> mine = byAddress.get(address);
		if (mine != null && mine.size() >= PER_ADDRESS) {
			return OptionalLong.of(retryAfter(mine.peekFirst(), ADDRESS_WINDOW, now));
		}
		if (global.size() >= GLOBAL) {
			return OptionalLong.of(retryAfter(global.peekFirst(), GLOBAL_WINDOW, now));
		}
		byAddress.computeIfAbsent(address, key -> new ArrayDeque<>()).addLast(now);
		global.addLast(now);
		return OptionalLong.empty();
	}

	private static void expire(Deque<Instant> times, Duration window, Instant now) {
		while (!times.isEmpty() && !times.peekFirst().plus(window).isAfter(now)) {
			times.removeFirst();
		}
	}

	private static long retryAfter(Instant oldest, Duration window, Instant now) {
		Duration left = Duration.between(now, oldest.plus(window));
		long seconds = left.getSeconds() + (left.getNano() > 0 ? 1 : 0);
		return Math.max(1, seconds);
	}

}
