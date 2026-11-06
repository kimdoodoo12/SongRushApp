package com.songrush.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

// Cycle 033 criterion 1: AudioTokens with a mutable test clock (no Spring context).
class AudioTokensTest {

	// A clock that only moves when the test advances it.
	static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-11-03T00:00:00Z");

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

	private final MutableClock clock = new MutableClock();

	private final AudioTokens tokens = new AudioTokens(clock);

	@Test
	void tokenIs22Base64UrlCharacters() {
		String token = tokens.issue(10, Duration.ofSeconds(30));
		assertThat(token).hasSize(22).matches("[A-Za-z0-9_-]{22}");
	}

	@Test
	void twoIssuesForTheSameThemeGiveDifferentTokens() {
		String first = tokens.issue(10, Duration.ofSeconds(30));
		String second = tokens.issue(10, Duration.ofSeconds(30));
		assertThat(first).isNotEqualTo(second);
		assertThat(tokens.resolve(first)).contains(10);
		assertThat(tokens.resolve(second)).contains(10);
	}

	@Test
	void resolveGivesTheThemeIdBeforeExpiry() {
		String token = tokens.issue(11, Duration.ofSeconds(10));
		assertThat(tokens.resolve(token)).contains(11);
		clock.advance(Duration.ofMillis(9_999));
		assertThat(tokens.resolve(token)).contains(11);
		assertThat(tokens.size()).isEqualTo(1);
	}

	@Test
	void tokenIsExpiredExactlyAtIssuedAtPlusTtl() {
		String token = tokens.issue(10, Duration.ofSeconds(10));
		clock.advance(Duration.ofSeconds(10));
		assertThat(tokens.resolve(token)).isEmpty();
	}

	@Test
	void expiredTokenResolvesEmptyAndIsRemoved() {
		String shortLived = tokens.issue(10, Duration.ofSeconds(1));
		String longLived = tokens.issue(11, Duration.ofSeconds(60));
		assertThat(tokens.size()).isEqualTo(2);

		clock.advance(Duration.ofSeconds(2));
		assertThat(tokens.resolve(shortLived)).isEmpty();
		assertThat(tokens.size()).isEqualTo(1);
		assertThat(tokens.resolve(longLived)).contains(11);
	}

	@Test
	void issueRemovesOtherExpiredEntries() {
		tokens.issue(10, Duration.ofSeconds(1));
		tokens.issue(11, Duration.ofSeconds(1));
		assertThat(tokens.size()).isEqualTo(2);

		clock.advance(Duration.ofSeconds(2));
		String fresh = tokens.issue(20, Duration.ofSeconds(30));
		assertThat(tokens.size()).isEqualTo(1);
		assertThat(tokens.resolve(fresh)).contains(20);
	}

	@Test
	void unknownTokenResolvesEmpty() {
		tokens.issue(10, Duration.ofSeconds(30));
		assertThat(tokens.resolve("AAAAAAAAAAAAAAAAAAAAAA")).isEmpty();
		assertThat(tokens.size()).isEqualTo(1);
	}

	@Test
	void zeroOrNegativeTtlThrows() {
		assertThatThrownBy(() -> tokens.issue(10, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> tokens.issue(10, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(tokens.size()).isZero();
	}

}
