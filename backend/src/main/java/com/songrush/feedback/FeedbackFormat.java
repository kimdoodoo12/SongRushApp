package com.songrush.feedback;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The cycle 082 rules shared by the public form and the admin send (cycle 083, D-097): the categories,
// the title and text limits, the request body reader, and the GitHub issue format, now built at send
// time from the stored record.
final class FeedbackFormat {

	private static final Logger log = LoggerFactory.getLogger(FeedbackFormat.class);

	static final int MAX_BODY_BYTES = 8 * 1024;

	static final int MAX_TITLE = 80;

	static final int MAX_TEXT = 2000;

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	record Category(String korean, String label) {
	}

	static final Map<String, Category> CATEGORIES = Map.of(
			"bug", new Category("버그", "bug"),
			"idea", new Category("건의", "enhancement"),
			"song", new Category("곡 요청", "song-request"),
			"other", new Category("기타", "question"));

	static final Set<String> LANGS = Set.of("ko", "en", "ja");

	static final Set<String> PAGES = Set.of("start", "lobby", "game");

	private static final String LABEL = "문의";

	private static final String FOOTER = "_사이트 문의하기로 접수됨_";

	private static final String FULLWIDTH_AT = String.valueOf((char) 0xFF20);

	private static final String FENCE = "````";

	private FeedbackFormat() {
	}

	// The parsed JSON object, or null when the body is unreadable, over 8 KB, not JSON or not an object.
	static JsonNode readBody(HttpServletRequest request) {
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

	// The trimmed title, or null when it is empty, over 80 UTF-16 code units or has a control character.
	static String title(String raw) {
		String title = raw.strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
			return null;
		}
		return title;
	}

	// The trimmed text, or null when it is empty, over 2000 code units or has a control character other
	// than newline and tab.
	static String text(String raw) {
		String text = raw.strip();
		if (text.isEmpty() || text.length() > MAX_TEXT || hasControl(text, true)) {
			return null;
		}
		return text;
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

	// The 082 issue for a stored record. editedTitle (already validated) replaces the stored title in the
	// issue title; the body then also keeps the original title in its own fence.
	static Issue issue(FeedbackRecord record, String editedTitle) {
		Category category = CATEGORIES.get(record.category());
		String shown = editedTitle == null ? record.title() : editedTitle;
		String title = "[" + LABEL + "/" + category.korean() + "] " + shown.replace("@", FULLWIDTH_AT);
		StringBuilder body = new StringBuilder()
				.append("- 분류: ").append(category.korean()).append(" (").append(record.category()).append(")\n")
				.append("- 언어: ").append(record.lang()).append("\n")
				.append("- 화면: ").append(record.page()).append("\n")
				.append("- 시각 (UTC): ").append(record.createdAt()).append("\n")
				.append("\n");
		if (editedTitle != null) {
			body.append("원래 제목:\n").append(fenced(record.title())).append("\n");
		}
		body.append(fenced(record.body()))
				.append("\n")
				.append(FOOTER).append("\n");
		return new Issue(title, body.toString(), List.of(LABEL, category.label()));
	}

	// The text in a 4-backtick fence; runs of 4 or more backticks inside are shortened to 3 so they
	// cannot close it.
	private static String fenced(String text) {
		return FENCE + "text\n" + text.replaceAll("`{4,}", "```") + "\n" + FENCE + "\n";
	}

	static ResponseEntity<Map<String, Object>> answer(HttpStatus status, Map<String, Object> body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}

}
