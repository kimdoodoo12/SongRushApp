package com.songrush.feedback;

import static com.songrush.feedback.FeedbackFormat.answer;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.OptionalLong;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

// POST /api/feedback (cycle 082, D-096; cycle 083, D-097): validates a player's inquiry, rate-limits it
// and stores it as a "new" record in the FeedbackStore. It never calls GitHub: the owner sends a record
// from the admin screen. The body is read here, not by a message converter, so a malformed or over-8 KB
// body still answers 400 {"error":"bad-feedback"} without the error dispatch.
// Order: store disabled (503) -> invalid (400) -> honeypot (201, nothing stored) -> rate limit (429)
// -> store: full (503 feedback-full), I/O failure (502), stored (201).
// Only a post that reaches the store is counted, whatever the outcome.
// Logs never carry the user's text.
@RestController
public class FeedbackController {

	private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

	private final FeedbackStore store;

	private final Clock clock;

	private final FeedbackRateLimiter limiter = new FeedbackRateLimiter();

	public FeedbackController(FeedbackStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	@PostMapping("/api/feedback")
	public ResponseEntity<Map<String, Object>> feedback(HttpServletRequest request) {
		if (!store.enabled()) {
			return answer(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "feedback-disabled"));
		}
		JsonNode json = FeedbackFormat.readBody(request);
		Feedback feedback = json == null ? null : Feedback.of(json);
		if (feedback == null) {
			return answer(HttpStatus.BAD_REQUEST, Map.of("error", "bad-feedback"));
		}
		if (feedback.honeypot()) {
			log.info("feedback: honeypot filled, ignored");
			return answer(HttpStatus.CREATED, Map.of("ok", true));
		}
		OptionalLong retryAfter = limiter.tryAcquire(String.valueOf(request.getRemoteAddr()), clock.instant());
		if (retryAfter.isPresent()) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header("Retry-After", Long.toString(retryAfter.getAsLong()))
					.contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("error", "rate-limited"));
		}
		try {
			FeedbackRecord record = store.add(feedback.category(), feedback.title(), feedback.text(),
					feedback.lang(), feedback.page());
			log.info("feedback: stored {} ({})", record.id(), record.category());
		}
		catch (FeedbackStore.FullException e) {
			log.warn("feedback: store full, post refused");
			return answer(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "feedback-full"));
		}
		catch (IOException | RuntimeException e) {
			log.error("feedback: post not stored ({})", e.getClass().getSimpleName());
			return answer(HttpStatus.BAD_GATEWAY, Map.of("error", "feedback-failed"));
		}
		return answer(HttpStatus.CREATED, Map.of("ok", true));
	}

	// A validated post: category is a known key, title and text are trimmed and within limits,
	// lang and page are a known value or "?".
	private record Feedback(String category, String title, String text, String lang, String page,
			boolean honeypot) {

		// Null when the post is invalid.
		static Feedback of(JsonNode json) {
			String category = string(json, "category");
			String title = string(json, "title");
			String text = string(json, "body");
			if (category == null || !FeedbackFormat.CATEGORIES.containsKey(category) || title == null
					|| text == null) {
				return null;
			}
			title = FeedbackFormat.title(title);
			text = FeedbackFormat.text(text);
			if (title == null || text == null) {
				return null;
			}
			String lang = string(json, "lang");
			String page = string(json, "page");
			JsonNode website = json.get("website");
			boolean honeypot = website != null && !website.isNull()
					&& !(website.isString() && website.asString().isEmpty());
			// Set.of rejects contains(null), hence the null checks.
			return new Feedback(category, title, text,
					lang != null && FeedbackFormat.LANGS.contains(lang) ? lang : "?",
					page != null && FeedbackFormat.PAGES.contains(page) ? page : "?", honeypot);
		}

		// The field's text, or null when it is missing or not a JSON string.
		private static String string(JsonNode json, String field) {
			JsonNode node = json.get(field);
			return node != null && node.isString() ? node.asString() : null;
		}

	}

}
