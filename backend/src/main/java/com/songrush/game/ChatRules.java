package com.songrush.game;

// The chat rules shared by Room.chat and the lobby's chat between games (docs/cycles/113-lobby-chat.md):
// the per-player token bucket (D-024, burst 5, 2 per second), the length limit and the invisible-text
// drop. Each caller keeps its own buckets and sends its own messages.
final class ChatRules {

	static final int MAX_CHAT = 200;

	// Chat token bucket (D-024), in thousandths of a token so the refill fraction carries over exactly.
	static final long ONE_TOKEN = 1000;

	static final long BUCKET_CAPACITY = 5 * ONE_TOKEN;

	static final long REFILL_PER_MS = 2;

	private ChatRules() {
	}

	// What to do with a chat line. A refused line names the error code that goes to its sender only.
	enum Verdict {

		PASS(null), DROP(null), RATE_LIMITED("rate-limited"), TOO_LONG("too-long");

		private final String errorCode;

		Verdict(String errorCode) {
			this.errorCode = errorCode;
		}

		// The error code for the sender, or null when nothing goes back.
		String errorCode() {
			return errorCode;
		}

	}

	// In order: a null text is dropped (no token spent); without a token, rate-limited; above MAX_CHAT,
	// too-long; a text that shows nothing is dropped silently; else the line passes.
	static Verdict check(Bucket bucket, long nowMs, String text) {
		if (text == null) {
			return Verdict.DROP;
		}
		if (!bucket.take(nowMs)) {
			return Verdict.RATE_LIMITED;
		}
		if (text.length() > MAX_CHAT) {
			return Verdict.TOO_LONG;
		}
		if (isInvisible(text)) {
			return Verdict.DROP;
		}
		return Verdict.PASS;
	}

	// True when the text shows nothing: empty, or only spaces, format/control characters,
	// Hangul fillers or variation selectors (such a chat would be broadcast as an empty line).
	static boolean isInvisible(String text) {
		return text.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)
				|| Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.CONTROL
				|| c == 0x115F || c == 0x1160 || c == 0x3164 || c == 0xFFA0
				|| (c >= 0xFE00 && c <= 0xFE0F) || (c >= 0xE0100 && c <= 0xE01EF));
	}

	// One player's bucket: thousandths of a token, created full, and when it was last refilled.
	static final class Bucket {

		private long tokens = BUCKET_CAPACITY;

		private long lastMs;

		Bucket(long lastMs) {
			this.lastMs = lastMs;
		}

		// Spends one token after the refill; false without one (the caller tells the sender).
		boolean take(long nowMs) {
			tokens = Math.min(BUCKET_CAPACITY, tokens + Math.max(0, nowMs - lastMs) * REFILL_PER_MS);
			lastMs = nowMs;
			if (tokens < ONE_TOKEN) {
				return false;
			}
			tokens -= ONE_TOKEN;
			return true;
		}

	}

}
