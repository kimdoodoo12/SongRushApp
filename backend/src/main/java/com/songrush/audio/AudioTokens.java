package com.songrush.audio;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// Short-lived random tokens that stand for a theme id, so an audio URL names nothing (cycle 033).
// A token is valid while now < issuedAt + ttl. Expired entries are removed on every issue and on resolve.
@Component
public class AudioTokens {

	private static final int TOKEN_BYTES = 16;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	private final Map<String, Entry> entries = new ConcurrentHashMap<>();

	public AudioTokens(Clock clock) {
		this.clock = clock;
	}

	public String issue(int themeId, Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("ttl must be positive: " + ttl);
		}
		Instant now = clock.instant();
		entries.values().removeIf(entry -> entry.isExpired(now));
		Instant expiresAt = now.plus(ttl);
		while (true) {
			byte[] bytes = new byte[TOKEN_BYTES];
			random.nextBytes(bytes);
			String token = ENCODER.encodeToString(bytes);
			// A collision of 128 random bits is practically impossible; retry rather than overwrite.
			if (entries.putIfAbsent(token, new Entry(themeId, expiresAt)) == null) {
				return token;
			}
		}
	}

	public Optional<Integer> resolve(String token) {
		if (token == null) {
			return Optional.empty();
		}
		Entry entry = entries.get(token);
		if (entry == null) {
			return Optional.empty();
		}
		if (entry.isExpired(clock.instant())) {
			entries.remove(token, entry);
			return Optional.empty();
		}
		return Optional.of(entry.themeId());
	}

	public int size() {
		return entries.size();
	}

	private record Entry(int themeId, Instant expiresAt) {

		boolean isExpired(Instant now) {
			return !now.isBefore(expiresAt);
		}

	}

}
