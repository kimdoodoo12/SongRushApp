package com.songrush.ws;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.game.Outbox;
import com.songrush.game.PlayerRef;
import com.songrush.game.Room;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The raw WebSocket at /ws for solo play (docs/cycles/037-solo-websocket.md). Each session has an
// OutboundQueue for every frame it is sent, and at most one Room, which runs on its own thread.
// No handler method throws: Spring would close the socket with 1011. Logs carry session ids and
// error codes only.
@Component
public class GameSocketHandler extends TextWebSocketHandler implements DisposableBean {

	static final int MAX_FRAME_BYTES = 4096;

	static final int QUEUE_CAPACITY = 256;

	static final int MIN_SONGS = 5;

	static final int MAX_SONGS = 30;

	static final int MAX_NICKNAME = 16;

	private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final AtomicInteger SENDER_THREADS = new AtomicInteger();

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final Clock clock;

	// A session drains on at most one thread at a time, so the pool grows only with open sessions,
	// and a slow client never delays another client's frames.
	private final ExecutorService senders = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "ws-send-" + SENDER_THREADS.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private final Map<String, OutboundQueue> queues = new ConcurrentHashMap<>();

	private final Map<String, Game> games = new ConcurrentHashMap<>();

	public GameSocketHandler(Catalog catalog, AudioTokens tokens, Clock clock) {
		this.catalog = catalog;
		this.tokens = tokens;
		this.clock = clock;
	}

	// One session's room: its thread, and the player map its outbox reads (cleared on discard).
	private final class Game {

		final String sessionId;

		final String playerId;

		final ExecutorGameScheduler scheduler;

		final Map<String, OutboundQueue> players = new ConcurrentHashMap<>();

		Room room;

		Game(String sessionId, String playerId) {
			this.sessionId = sessionId;
			this.playerId = playerId;
			this.scheduler = new ExecutorGameScheduler("room of session " + sessionId);
		}

	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		try {
			queues.put(session.getId(), new OutboundQueue(new SessionSink(session), senders, QUEUE_CAPACITY));
		}
		catch (RuntimeException e) {
			log.error("session {} setup failed: {}", session.getId(), e.getClass().getName());
		}
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		String sessionId = session.getId();
		try {
			if (message.getPayloadLength() > MAX_FRAME_BYTES) {
				sendError(sessionId, "too-large");
				return;
			}
			JsonNode node = parse(message.getPayload());
			if (node == null) {
				sendError(sessionId, "bad-message");
				return;
			}
			switch (node.get("type").stringValue()) {
				case "ping" -> ping(sessionId, node);
				case "create" -> create(sessionId, node);
				case "ready" -> ready(sessionId, node);
				case "chat" -> chat(sessionId, node);
				case "leave" -> leave(sessionId);
				default -> sendError(sessionId, "bad-message");
			}
		}
		catch (RuntimeException e) {
			log.error("session {} message failed: {}", sessionId, e.getClass().getName());
		}
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		log.warn("session {} transport error: {}", session.getId(), exception.getClass().getName());
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		String sessionId = session.getId();
		try {
			OutboundQueue queue = queues.remove(sessionId);
			if (queue != null) {
				queue.stop();
			}
			Game game = games.get(sessionId);
			if (game != null) {
				discard(game);
			}
		}
		catch (RuntimeException e) {
			log.error("session {} cleanup failed: {}", sessionId, e.getClass().getName());
		}
	}

	@Override
	public void destroy() {
		games.values().forEach(this::discard);
		senders.shutdownNow();
	}

	// A JSON object with a string "type", or null.
	private static JsonNode parse(String payload) {
		JsonNode node;
		try {
			node = MAPPER.readTree(payload);
		}
		catch (JacksonException e) {
			return null;
		}
		if (node == null || !node.isObject() || !node.path("type").isString()) {
			return null;
		}
		return node;
	}

	private void ping(String sessionId, JsonNode node) {
		Map<String, Object> pong = new LinkedHashMap<>();
		pong.put("type", "pong");
		if (node.has("id")) {
			pong.put("id", node.get("id"));
		}
		pong.put("serverTime", clock.millis());
		send(sessionId, pong);
	}

	private void create(String sessionId, JsonNode node) {
		JsonNode nicknameNode = node.path("nickname");
		JsonNode songCountNode = node.path("songCount");
		if (!nicknameNode.isString() || !songCountNode.isInt()) {
			sendError(sessionId, "bad-message");
			return;
		}
		if (games.containsKey(sessionId)) {
			sendError(sessionId, "already-in-room");
			return;
		}
		String nickname = nicknameNode.stringValue().trim();
		if (nickname.isEmpty() || nickname.length() > MAX_NICKNAME) {
			sendError(sessionId, "bad-nickname");
			return;
		}
		int songCount = songCountNode.intValue();
		if (songCount < MIN_SONGS || songCount > MAX_SONGS) {
			sendError(sessionId, "bad-song-count");
			return;
		}
		OutboundQueue queue = queues.get(sessionId);
		if (queue == null) {
			return;
		}
		Game game = new Game(sessionId, UUID.randomUUID().toString());
		game.players.put(game.playerId, queue);
		Outbox outbox = new GameOverWatch(game, new SocketOutbox(game.players));
		game.room = new Room(catalog, tokens, game.scheduler, clock, new SplittableRandom(), outbox,
				List.of(new PlayerRef(game.playerId, nickname)));
		if (games.putIfAbsent(sessionId, game) != null) {
			game.scheduler.shutdownNow();
			sendError(sessionId, "already-in-room");
			return;
		}
		try {
			game.scheduler.execute(() -> {
				game.room.start(songCount);
				if (game.room.phase() == Room.Phase.LOBBY) {
					// The room sent its error (not-enough-songs); it never started.
					discard(game);
				}
			});
		}
		catch (RejectedExecutionException e) {
			discard(game);
			sendError(sessionId, "no-room");
		}
	}

	private void ready(String sessionId, JsonNode node) {
		JsonNode index = node.path("index");
		if (!index.isInt()) {
			sendError(sessionId, "bad-message");
			return;
		}
		int i = index.intValue();
		onRoom(sessionId, game -> game.room.ready(game.playerId, i));
	}

	private void chat(String sessionId, JsonNode node) {
		JsonNode text = node.path("text");
		if (!text.isString()) {
			sendError(sessionId, "bad-message");
			return;
		}
		String t = text.stringValue();
		onRoom(sessionId, game -> game.room.chat(game.playerId, t));
	}

	private void leave(String sessionId) {
		Game game = games.get(sessionId);
		if (game != null) {
			discard(game);
		}
	}

	// Runs the action on the session's room thread, or answers no-room.
	private void onRoom(String sessionId, Consumer<Game> action) {
		Game game = games.get(sessionId);
		if (game == null) {
			sendError(sessionId, "no-room");
			return;
		}
		try {
			game.scheduler.execute(() -> action.accept(game));
		}
		catch (RejectedExecutionException e) {
			sendError(sessionId, "no-room");
		}
	}

	// Removes the room's own entry only, stops its sends, and shuts its thread down. Idempotent.
	private void discard(Game game) {
		games.remove(game.sessionId, game);
		game.players.clear();
		game.scheduler.shutdownNow();
	}

	private void sendError(String sessionId, String code) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("type", "error");
		error.put("code", code);
		send(sessionId, error);
	}

	private void send(String sessionId, Map<String, Object> message) {
		OutboundQueue queue = queues.get(sessionId);
		if (queue == null) {
			return;
		}
		try {
			queue.offer(MAPPER.writeValueAsString(message));
		}
		catch (JacksonException e) {
			log.error("session {} reply not serialized: {}", sessionId, e.getClass().getName());
		}
	}

	// Discards the room after it sends gameOver (on the room thread, after the message is queued).
	private final class GameOverWatch implements Outbox {

		private final Game game;

		private final Outbox delegate;

		GameOverWatch(Game game, Outbox delegate) {
			this.game = game;
			this.delegate = delegate;
		}

		@Override
		public void toPlayer(String playerId, Map<String, Object> message) {
			delegate.toPlayer(playerId, message);
		}

		@Override
		public void toAll(Map<String, Object> message) {
			delegate.toAll(message);
			if (message != null && "gameOver".equals(message.get("type"))) {
				discard(game);
			}
		}

	}

	// The real socket behind an OutboundQueue; only the queue's drain calls send.
	private static final class SessionSink implements OutboundQueue.Sink {

		private final WebSocketSession session;

		SessionSink(WebSocketSession session) {
			this.session = session;
		}

		@Override
		public void send(String text) throws IOException {
			session.sendMessage(new TextMessage(text));
		}

		@Override
		public void close(int status) {
			try {
				session.close(new CloseStatus(status));
			}
			catch (IOException e) {
				log.warn("session {} close failed: {}", session.getId(), e.getClass().getName());
			}
		}

		@Override
		public String toString() {
			return "session " + session.getId();
		}

	}

}
