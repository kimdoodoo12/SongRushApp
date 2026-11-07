package com.songrush.feedback;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

// One stored inquiry (cycle 083, D-097). status is "new", "sent" or "hidden"; issueNumber and issueUrl
// are null until the item is sent. The same field names are used in feedback.json and in the admin API,
// with createdAt and updatedAt written as Instant.toString().
public record FeedbackRecord(String id, Instant createdAt, String category, String title, String body,
		String lang, String page, String status, Integer issueNumber, String issueUrl, Instant updatedAt) {

	static final String NEW = "new";

	static final String SENT = "sent";

	static final String HIDDEN = "hidden";

	static final Set<String> STATUSES = Set.of(NEW, SENT, HIDDEN);

	FeedbackRecord withStatus(String newStatus, Instant now) {
		return new FeedbackRecord(id, createdAt, category, title, body, lang, page, newStatus, issueNumber, issueUrl,
				now);
	}

	FeedbackRecord sent(CreatedIssue issue, Instant now) {
		return new FeedbackRecord(id, createdAt, category, title, body, lang, page, SENT, issue.number(), issue.url(),
				now);
	}

	// Insertion-ordered, nulls kept: the JSON object for the file and the admin API.
	Map<String, Object> toJson() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("createdAt", createdAt.toString());
		map.put("category", category);
		map.put("title", title);
		map.put("body", body);
		map.put("lang", lang);
		map.put("page", page);
		map.put("status", status);
		map.put("issueNumber", issueNumber);
		map.put("issueUrl", issueUrl);
		map.put("updatedAt", updatedAt.toString());
		return map;
	}

	// The record in a feedback.json item, or null when any field is missing or has the wrong type.
	static FeedbackRecord fromJson(JsonNode item) {
		if (item == null || !item.isObject()) {
			return null;
		}
		String id = text(item, "id");
		Instant createdAt = instant(item, "createdAt");
		Instant updatedAt = instant(item, "updatedAt");
		String category = text(item, "category");
		String title = text(item, "title");
		String body = text(item, "body");
		String lang = text(item, "lang");
		String page = text(item, "page");
		String status = text(item, "status");
		if (id == null || id.isEmpty() || createdAt == null || updatedAt == null || category == null || title == null
				|| body == null || lang == null || page == null || status == null || !STATUSES.contains(status)) {
			return null;
		}
		JsonNode number = item.get("issueNumber");
		Integer issueNumber;
		if (number == null || number.isNull()) {
			issueNumber = null;
		}
		else if (number.isIntegralNumber() && number.canConvertToInt()) {
			issueNumber = number.intValue();
		}
		else {
			return null;
		}
		JsonNode url = item.get("issueUrl");
		String issueUrl;
		if (url == null || url.isNull()) {
			issueUrl = null;
		}
		else if (url.isString()) {
			issueUrl = url.asString();
		}
		else {
			return null;
		}
		return new FeedbackRecord(id, createdAt, category, title, body, lang, page, status, issueNumber, issueUrl,
				updatedAt);
	}

	private static String text(JsonNode item, String field) {
		JsonNode node = item.get(field);
		return node != null && node.isString() ? node.asString() : null;
	}

	private static Instant instant(JsonNode item, String field) {
		String value = text(item, field);
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

}
