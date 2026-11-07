package com.songrush.feedback;

import static com.songrush.feedback.FeedbackFormat.answer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;

// The owner's feedback inbox under /admin/api (cycle 083, D-097). AdminApiInterceptor runs first on
// every handler here: the admin key (404), the CSRF headers on POSTs (403) and Cache-Control: no-store.
// send checks, in order: unknown id (404), sent (409 already-sent), in flight (409 in-flight), the
// edited title (400), the token (503 github-disabled); then GitHub is called without the store lock.
// Logs never carry the user's text.
@RestController
public class AdminFeedbackController {

	private static final Logger log = LoggerFactory.getLogger(AdminFeedbackController.class);

	private static final Set<String> LIST_STATUSES = Set.of("new", "sent", "hidden", "all");

	private final FeedbackStore store;

	private final IssueSender sender;

	private final FeedbackProperties properties;

	public AdminFeedbackController(FeedbackStore store, IssueSender sender, FeedbackProperties properties) {
		this.store = store;
		this.sender = sender;
		this.properties = properties;
	}

	@GetMapping("/admin/api/feedback")
	public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "new") String status) {
		if (!LIST_STATUSES.contains(status)) {
			return answer(HttpStatus.BAD_REQUEST, Map.of("error", "bad-status"));
		}
		List<Map<String, Object>> items = store.list(status).stream().map(FeedbackRecord::toJson).toList();
		return answer(HttpStatus.OK, Map.of("items", items));
	}

	@PostMapping("/admin/api/feedback/{id}/send")
	public ResponseEntity<Map<String, Object>> send(@PathVariable String id, HttpServletRequest request) {
		if (!store.enabled()) {
			return notFound();
		}
		EditedTitle edited = EditedTitle.of(FeedbackFormat.readBody(request));
		FeedbackRecord record;
		try {
			record = store.beginSend(id);
		}
		catch (FeedbackStore.StateException e) {
			return refused(e);
		}
		try {
			if (!edited.valid()) {
				return answer(HttpStatus.BAD_REQUEST, Map.of("error", "bad-feedback"));
			}
			if (!properties.hasToken()) {
				return answer(HttpStatus.SERVICE_UNAVAILABLE, Map.of("error", "github-disabled"));
			}
			CreatedIssue created;
			try {
				created = sender.send(FeedbackFormat.issue(record, edited.title()));
			}
			catch (IssueSendException | RuntimeException e) {
				log.warn("feedback: {} not sent ({})", id, e.getClass().getSimpleName());
				return answer(HttpStatus.BAD_GATEWAY, Map.of("error", "feedback-failed"));
			}
			try {
				FeedbackRecord sent = store.markSent(id, created);
				log.info("feedback: {} sent as issue #{}", id, created.number());
				return answer(HttpStatus.OK, sent.toJson());
			}
			catch (IOException e) {
				log.error("feedback: {} sent as issue #{} but the store write failed ({}); kept as sent in memory",
						id, created.number(), e.getClass().getSimpleName());
				Map<String, Object> body = store.get(id).orElseThrow().toJson();
				body.put("persisted", false);
				return answer(HttpStatus.OK, body);
			}
		}
		finally {
			store.endSend(id);
		}
	}

	@PostMapping("/admin/api/feedback/{id}/hide")
	public ResponseEntity<Map<String, Object>> hide(@PathVariable String id) {
		return change(id, true);
	}

	@PostMapping("/admin/api/feedback/{id}/unhide")
	public ResponseEntity<Map<String, Object>> unhide(@PathVariable String id) {
		return change(id, false);
	}

	private ResponseEntity<Map<String, Object>> change(String id, boolean hide) {
		if (!store.enabled()) {
			return notFound();
		}
		try {
			FeedbackRecord record = hide ? store.hide(id) : store.unhide(id);
			return answer(HttpStatus.OK, record.toJson());
		}
		catch (FeedbackStore.StateException e) {
			return refused(e);
		}
		catch (IOException e) {
			log.error("feedback: {} not {} ({})", id, hide ? "hidden" : "restored", e.getClass().getSimpleName());
			return answer(HttpStatus.BAD_GATEWAY, Map.of("error", "feedback-failed"));
		}
	}

	// A disabled store holds no records.
	private static ResponseEntity<Map<String, Object>> notFound() {
		return answer(HttpStatus.NOT_FOUND, Map.of("error", "not-found"));
	}

	private static ResponseEntity<Map<String, Object>> refused(FeedbackStore.StateException e) {
		return switch (e.reason()) {
			case NOT_FOUND -> notFound();
			case ALREADY_SENT -> answer(HttpStatus.CONFLICT, Map.of("error", "already-sent"));
			case IN_FLIGHT -> answer(HttpStatus.CONFLICT, Map.of("error", "in-flight"));
		};
	}

	// The send body: {} or {"title": null} keeps the stored title; {"title": "..."} must pass the 082
	// title rules. Anything else (not an object, a non-string title) is invalid.
	private record EditedTitle(boolean valid, String title) {

		static EditedTitle of(JsonNode json) {
			if (json == null) {
				return new EditedTitle(false, null);
			}
			JsonNode node = json.get("title");
			if (node == null || node.isNull()) {
				return new EditedTitle(true, null);
			}
			if (!node.isString()) {
				return new EditedTitle(false, null);
			}
			String title = FeedbackFormat.title(node.asString());
			return new EditedTitle(title != null, title);
		}

	}

}
