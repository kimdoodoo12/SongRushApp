package com.songrush.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// An Outbox that records every message in send order. to is the player id, or null for toAll.
final class RecordingOutbox implements Outbox {

	record Sent(String to, Map<String, Object> message) {

		boolean toAll() {
			return to == null;
		}

		String type() {
			return (String) message.get("type");
		}

	}

	private final List<Sent> sent = new ArrayList<>();

	@Override
	public void toPlayer(String playerId, Map<String, Object> message) {
		Objects.requireNonNull(playerId, "playerId");
		sent.add(new Sent(playerId, Collections.unmodifiableMap(new LinkedHashMap<>(message))));
	}

	@Override
	public void toAll(Map<String, Object> message) {
		sent.add(new Sent(null, Collections.unmodifiableMap(new LinkedHashMap<>(message))));
	}

	List<Sent> all() {
		return List.copyOf(sent);
	}

	int mark() {
		return sent.size();
	}

	List<Sent> since(int mark) {
		return List.copyOf(sent.subList(mark, sent.size()));
	}

	List<Sent> ofType(String type) {
		return sent.stream().filter(s -> type.equals(s.type())).toList();
	}

	// The last message of the type; fails when there is none.
	Sent last(String type) {
		List<Sent> matching = ofType(type);
		if (matching.isEmpty()) {
			throw new AssertionError("no '" + type + "' message was sent; sent types: "
					+ sent.stream().map(Sent::type).toList());
		}
		return matching.get(matching.size() - 1);
	}

}
