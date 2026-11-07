package com.songrush.feedback;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// POST /api/feedback (cycle 082, D-096): validates a player's inquiry, rate-limits it and files it as a
// GitHub issue. The body is read here, not by a message converter, so a malformed or over-8 KB body
// still answers 400 {"error":"bad-feedback"} without the error dispatch.
// Order: disabled (503) -> invalid (400) -> honeypot (201, nothing sent) -> rate limit (429) -> send.
// Only a post that reaches the send is counted, whether the send then succeeds or fails.
// Logs never carry the user's text.
@RestController
public class FeedbackController {

	private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

	static final int MAX_BODY_BYTES = 8 * 1024;

	static final int MAX_TITLE = 80;

	static final int MAX_TEXT = 2000;

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private record Category(String korean, String label) {
	}

	private static final Map<String, Category> CATEGORIES = Map.of(
			"bug", new Category("버그", "bug"),
			"idea", new Category("건의", "enhancement"),
			"song", new Category("곡 요청", "song-request"),
			"other", new Category("기타", "question"));

	private static final Set<String> LANGS = Set.of("ko", "en", "ja");

	private static final Set<String> PAGES = Set.of("start", "lobby", "game");

	private static final String LABEL = "문의";

	private static final String FOOTER = "_사이트 문의하기로 접수됨_";

	private static final String FULLWIDTH_AT = String.valueOf((char) 0xFF20);

	private static final String FENCE = "````";

	private final FeedbackProperties properties;

	private final IssueSender sender;

	private final Clock clock;

	private final FeedbackRateLimiter limiter = new FeedbackRateLimiter();

	public FeedbackController(FeedbackProperties properties, IssueSender sender, Clock clock) {
		this.properties = properties;
		this.sender = sender;
		this.clock = clock;
	}

	@PostMapping("/api/feedback")
	public ResponseEntity<Map<String, Object>> feedback(HttpServletRequest request) {
		if (!properties.enabled()) {
			return answer(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "feedback-disabled"));
		}
		JsonNode json = readBody(request);
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
			sender.send(issue(feedback));
		}
		catch (IssueSendException | RuntimeException e) {
			log.warn("feedback: issue not created ({})", e.getClass().getSimpleName());
			return answer(HttpStatus.BAD_GATEWAY, Map.of("error", "feedback-failed"));
		}
		return answer(HttpStatus.CREATED, Map.of("ok", true));
	}

	// The parsed JSON object, or null when the body is unreadable, over 8 KB, not JSON or not an object.
	private static JsonNode readBody(HttpServletRequest request) {
		byte[] bytes;
		try (InputStream in = request.getInputStream()) {
			bytes = in.readNBytes(MAX_BODY_BYTES + 1);
		}
		catch (IOException e) {
			log.debug("feedback: body unreadable ({})", e.getClass().getSimpleName());
			return null;
		}
		if (bytes.length > MAX_BODY_BYTES) {
			return null;
		}
		try {
			JsonNode json = MAPPER.readTree(bytes);
			return json != null && json.isObject() ? json : null;
		}
		catch (JacksonException e) {
			return null;
		}
	}

	private Issue issue(Feedback feedback) {
		Category category = CATEGORIES.get(feedback.category());
		String title = "[" + LABEL + "/" + category.korean() + "] " + feedback.title().replace("@", FULLWIDTH_AT);
		String text = feedback.text().replaceAll("`{4,}", "```");
		String body = "- 분류: " + category.korean() + " (" + feedback.category() + ")\n"
				+ "- 언어: " + feedback.lang() + "\n"
				+ "- 화면: " + feedback.page() + "\n"
				+ "- 시각 (UTC): " + clock.instant() + "\n"
				+ "\n"
				+ FENCE + "text\n" + text + "\n" + FENCE + "\n"
				+ "\n"
				+ FOOTER + "\n";
		return new Issue(title, body, List.of(LABEL, category.label()));
	}

	private static ResponseEntity<Map<String, Object>> answer(HttpStatus status, Map<String, Object> body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
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
			if (category == null || !CATEGORIES.containsKey(category) || title == null || text == null) {
				return null;
			}
			title = title.strip();
			text = text.strip();
			if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
				return null;
			}
			if (text.isEmpty() || text.length() > MAX_TEXT || hasControl(text, true)) {
				return null;
			}
			String lang = string(json, "lang");
			String page = string(json, "page");
			JsonNode website = json.get("website");
			boolean honeypot = website != null && !website.isNull()
					&& !(website.isString() && website.asString().isEmpty());
			// Set.of rejects contains(null), hence the null checks.
			return new Feedback(category, title, text, lang != null && LANGS.contains(lang) ? lang : "?",
					page != null && PAGES.contains(page) ? page : "?", honeypot);
		}

		// The field's text, or null when it is missing or not a JSON string.
		private static String string(JsonNode json, String field) {
			JsonNode node = json.get(field);
			return node != null && node.isString() ? node.asString() : null;
		}

		private static boolean hasControl(String value, boolean allowNewlineAndTab) {
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (allowNewlineAndTab && (c == '\n' || c == '\t')) {
					continue;
				}
				if (Character.isISOControl(c)) {
					return true;
				}
			}
			return false;
		}

	}

}
