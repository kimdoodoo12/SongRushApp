package com.songrush.game;

import java.util.Map;

// Where a room sends its messages. Every message has a "type" key; values are String, Integer,
// Long, Boolean, null, or List / Map of those, so Jackson can serialize them.
public interface Outbox {

	void toPlayer(String playerId, Map<String, Object> message);

	void toAll(Map<String, Object> message);

}
