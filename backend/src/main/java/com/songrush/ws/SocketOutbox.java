package com.songrush.ws;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.songrush.game.Outbox;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The Outbox a room sends through: each message becomes JSON and is offered to the players'
// queues. The map is read at each call, so players can be added or removed later. Never throws
// into the room (cycle 036 review M2); failures are logged with the message type only.
public final class SocketOutbox implements Outbox {

	private static final Logger log = LoggerFactory.getLogger(SocketOutbox.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private final Map<String, OutboundQueue> queuesByPlayerId;

	public SocketOutbox(Map<String, OutboundQueue> queuesByPlayerId) {
		this.queuesByPlayerId = queuesByPlayerId;
	}

	@Override
	public void toPlayer(String playerId, Map<String, Object> message) {
		try {
			OutboundQueue queue = queuesByPlayerId.get(playerId);
			if (queue == null) {
				return;
			}
			queue.offer(MAPPER.writeValueAsString(message));
		}
		catch (RuntimeException e) {
			log.warn("dropped a '{}' message to one player: {}", typeOf(message), e.getClass().getSimpleName());
		}
	}

	@Override
	public void toAll(Map<String, Object> message) {
		try {
			String json = MAPPER.writeValueAsString(message);
			for (OutboundQueue queue : queuesByPlayerId.values()) {
				queue.offer(json);
			}
		}
		catch (RuntimeException e) {
			log.warn("dropped a '{}' message to all players: {}", typeOf(message), e.getClass().getSimpleName());
		}
	}

	private static Object typeOf(Map<String, Object> message) {
		try {
			return message == null ? null : message.get("type");
		}
		catch (RuntimeException e) {
			return null;
		}
	}

}
