package com.songrush.ws;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
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
import com.songrush.game.Lobby;
import com.songrush.game.Room;
import com.songrush.game.RoomCodes;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The raw WebSocket at /ws (docs/cycles/037-solo-websocket.md, 040-rooms-lobby.md,
// 042-reconnect.md). Each session has an OutboundQueue for every frame it is sent, and at most one
// seat in a Lobby; a Lobby and its games run on the lobby's own thread. A seated player gets a
// private token for rejoin; per lobby, each player is bound to the one session that holds the seat.
// No handler method throws: Spring would close the socket with 1011. Logs carry session ids and
// error codes only, never a token.
@Component
public class GameSocketHandler extends TextWebSocketHandler implements DisposableBean {

	static final int MAX_FRAME_BYTES = 4096;

	static final int QUEUE_CAPACITY = 256;

	static final int MIN_SONGS = 5;

	static final int MAX_SONGS = 30;

	static final int MAX_NICKNAME = 16;

	static final int MAX_LOBBIES = 200;

	static final int TOKEN_BYTES = 16;

	static final CloseStatus REPLACED = new CloseStatus(4001, "replaced");

	private static final Logger log = LoggerFactory.getLogger(GameSocketHandler.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final AtomicInteger SENDER_THREADS = new AtomicInteger();

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final Clock clock;

	// Thread-safe; draws every room code.
	private final SecureRandom codes = new SecureRandom();

	// A session drains on at most one thread at a time, so the pool grows only with open sessions,
	// and a slow client never delays another client's frames.
	private final ExecutorService senders = Executors.newCachedThreadPool(task -> {
		Thread thread = new Thread(task, "ws-send-" + SENDER_THREADS.incrementAndGet());
		thread.setDaemon(true);
		return thread;
	});

	private final Map<String, OutboundQueue> queues = new ConcurrentHashMap<>();

	// Open sessions by id, so a replaced session can be closed from another session's thread.
	private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

	// Rejoin tokens of seated players; dropped on leave, a failed rejoin, and lobby close.
	private final Map<String, Login> logins = new ConcurrentHashMap<>();

	// Lobbies by room code; a code is removed when its lobby closes.
	private final Map<String, Hosted> registry = new ConcurrentHashMap<>();

	// Each session's seat in a lobby, reserved with putIfAbsent before the lobby task runs.
	private final Map<String, Seat> seats = new ConcurrentHashMap<>();

	// Lobbies made and not yet closed, capped at MAX_LOBBIES.
	private final AtomicInteger lobbies = new AtomicInteger();

	public GameSocketHandler(Catalog catalog, AudioTokens tokens, Clock clock) {
		this.catalog = catalog;
		this.tokens = tokens;
		this.clock = clock;
	}

	// One lobby: its thread, and the player map its outbox reads.
	private final class Hosted {

		final String code;

		final ExecutorGameScheduler scheduler;

		final Map<String, OutboundQueue> players = new ConcurrentHashMap<>();

		// Player id to the session holding the seat. A close, leave or rejoin changes it only with
		// compute, which also submits the lobby task that goes with the change, so those tasks run
		// in binding order.
		final Map<String, String> bindings = new ConcurrentHashMap<>();

		final Lobby lobby;

		Hosted(String code) {
			this.code = code;
			this.scheduler = new ExecutorGameScheduler("lobby " + code);
			this.lobby = new Lobby(code, catalog, tokens, scheduler, clock, new SplittableRandom(),
					new SocketOutbox(players), this::closed);
		}

		// The lobby's onClosed (on its thread): frees the code, the sessions and the thread. Also
		// used once for a lobby abandoned before anyone joined it.
		void closed() {
			registry.remove(code, this);
			seats.values().removeIf(seat -> seat.hosted() == this);
			logins.values().removeIf(login -> login.hosted() == this);
			bindings.clear();
			players.clear();
			lobbies.decrementAndGet();
			scheduler.shutdownNow();
		}

	}

	// A session's place in a lobby: the player id, its rejoin token, and this session's queue.
	private record Seat(Hosted hosted, String playerId, String token, OutboundQueue queue) {

		// Never prints the token.
		@Override
		public String toString() {
			return "Seat[code=" + hosted.code + "]";
		}

	}

	// What a rejoin token stands for.
	private record Login(Hosted hosted, String playerId) {
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		try {
			queues.put(session.getId(), new OutboundQueue(new SessionSink(session), senders, QUEUE_CAPACITY));
			sessions.put(session.getId(), session);
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
				case "join" -> join(sessionId, node);
				case "start" -> start(sessionId, node);
				case "ready" -> ready(sessionId, node);
				case "chat" -> chat(sessionId, node);
				case "leave" -> leave(sessionId);
				case "rejoin" -> rejoin(sessionId, node);
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
			sessions.remove(sessionId);
			Seat seat = seats.remove(sessionId);
			if (seat != null) {
				// Not a leave: the token stays valid for rejoin during the lobby's grace period.
				unbind(sessionId, seat, () -> seat.hosted().lobby.disconnect(seat.playerId()));
			}
		}
		catch (RuntimeException e) {
			log.error("session {} cleanup failed: {}", sessionId, e.getClass().getName());
		}
	}

	@Override
	public void destroy() {
		registry.values().forEach(hosted -> hosted.scheduler.shutdownNow());
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

	// create{nickname} makes a lobby with the creator as host. create{nickname, songCount} is the
	// solo path: the same, and the creator starts at once. Only an absent songCount means a lobby.
	private void create(String sessionId, JsonNode node) {
		JsonNode nicknameNode = node.path("nickname");
		boolean solo = node.has("songCount");
		JsonNode songCountNode = node.path("songCount");
		if (!nicknameNode.isString() || (solo && !songCountNode.isInt())) {
			sendError(sessionId, "bad-message");
			return;
		}
		if (seats.containsKey(sessionId)) {
			sendError(sessionId, "already-in-room");
			return;
		}
		// Checked here, before a lobby is made, so bad-nickname never leaves an empty lobby behind.
		String nickname = nicknameNode.stringValue().trim();
		if (nickname.isEmpty() || nickname.length() > MAX_NICKNAME) {
			sendError(sessionId, "bad-nickname");
			return;
		}
		int songCount = solo ? songCountNode.intValue() : 0;
		if (solo && (songCount < MIN_SONGS || songCount > MAX_SONGS)) {
			sendError(sessionId, "bad-song-count");
			return;
		}
		OutboundQueue queue = queues.get(sessionId);
		if (queue == null) {
			return;
		}
		Hosted hosted = newLobby();
		if (hosted == null) {
			sendError(sessionId, "server-full");
			return;
		}
		Seat seat = new Seat(hosted, UUID.randomUUID().toString(), newToken(), queue);
		if (seats.putIfAbsent(sessionId, seat) != null) {
			hosted.closed();
			sendError(sessionId, "already-in-room");
			return;
		}
		enter(sessionId, queue, seat, nickname, solo ? songCount : null);
	}

	private void join(String sessionId, JsonNode node) {
		JsonNode codeNode = node.path("code");
		JsonNode nicknameNode = node.path("nickname");
		if (!codeNode.isString() || !nicknameNode.isString()) {
			sendError(sessionId, "bad-message");
			return;
		}
		if (seats.containsKey(sessionId)) {
			sendError(sessionId, "already-in-room");
			return;
		}
		Hosted hosted = registry.get(codeNode.stringValue().trim().toUpperCase(Locale.ROOT));
		if (hosted == null) {
			sendError(sessionId, "room-not-found");
			return;
		}
		OutboundQueue queue = queues.get(sessionId);
		if (queue == null) {
			return;
		}
		Seat seat = new Seat(hosted, UUID.randomUUID().toString(), newToken(), queue);
		if (seats.putIfAbsent(sessionId, seat) != null) {
			sendError(sessionId, "already-in-room");
			return;
		}
		// The lobby checks the nickname itself, after room-not-found for a closed lobby.
		enter(sessionId, queue, seat, nicknameNode.stringValue(), null);
	}

	// Joins the reserved seat's lobby on its thread, then sends the token, and for the solo path
	// starts at once. The seat is released if the lobby refuses the join, or if the solo start stays
	// in the lobby. The player id is fresh, so no other session can be bound to it yet.
	private void enter(String sessionId, OutboundQueue queue, Seat seat, String nickname, Integer soloSongCount) {
		Hosted hosted = seat.hosted();
		hosted.players.put(seat.playerId(), queue);
		hosted.bindings.put(seat.playerId(), sessionId);
		try {
			hosted.scheduler.execute(() -> {
				if (hosted.lobby.join(seat.playerId(), nickname) == null) {
					release(sessionId, seat);
					return;
				}
				logins.put(seat.token(), new Login(hosted, seat.playerId()));
				Map<String, Object> token = new LinkedHashMap<>();
				token.put("type", "token");
				token.put("token", seat.token());
				offer(queue, sessionId, token);
				if (soloSongCount != null) {
					hosted.lobby.start(seat.playerId(), soloSongCount);
					if (hosted.lobby.phase() == Room.Phase.LOBBY) {
						// The room sent its error (not-enough-songs); as in cycle 037, no room remains.
						hosted.lobby.leave(seat.playerId());
						release(sessionId, seat);
					}
				}
			});
		}
		catch (RejectedExecutionException e) {
			release(sessionId, seat);
			sendError(sessionId, "room-not-found");
		}
	}

	// A registered lobby with a fresh code on its own thread, or null when MAX_LOBBIES exist.
	private Hosted newLobby() {
		if (lobbies.incrementAndGet() > MAX_LOBBIES) {
			lobbies.decrementAndGet();
			return null;
		}
		try {
			while (true) {
				String code = RoomCodes.next(codes, registry::containsKey);
				Hosted hosted = new Hosted(code);
				if (registry.putIfAbsent(code, hosted) == null) {
					return hosted;
				}
				// Another create took the code between the check and the put.
				hosted.scheduler.shutdownNow();
			}
		}
		catch (RuntimeException e) {
			lobbies.decrementAndGet();
			throw e;
		}
	}

	private void start(String sessionId, JsonNode node) {
		JsonNode songCount = node.path("songCount");
		if (!songCount.isInt()) {
			sendError(sessionId, "bad-message");
			return;
		}
		int count = songCount.intValue();
		onLobby(sessionId, seat -> seat.hosted().lobby.start(seat.playerId(), count));
	}

	private void ready(String sessionId, JsonNode node) {
		JsonNode index = node.path("index");
		if (!index.isInt()) {
			sendError(sessionId, "bad-message");
			return;
		}
		int i = index.intValue();
		onLobby(sessionId, seat -> seat.hosted().lobby.ready(seat.playerId(), i));
	}

	private void chat(String sessionId, JsonNode node) {
		JsonNode text = node.path("text");
		if (!text.isString()) {
			sendError(sessionId, "bad-message");
			return;
		}
		String t = text.stringValue();
		onLobby(sessionId, seat -> seat.hosted().lobby.chat(seat.playerId(), t));
	}

	// Frees the session's seat and queue entry at once (cycle 040 review M2: no frame after leave),
	// then leaves the lobby on its thread and drops the token.
	private void leave(String sessionId) {
		Seat seat = seats.remove(sessionId);
		if (seat == null) {
			return;
		}
		unbind(sessionId, seat, () -> seat.hosted().lobby.leave(seat.playerId()));
		logins.remove(seat.token());
	}

	// If the player is still bound to this session: clears the binding, removes this session's
	// queue, and submits the lobby action, all inside compute so that a concurrent rejoin's
	// reconnect runs after it. A binding that points to another session (replaced) is left alone.
	private void unbind(String sessionId, Seat seat, Runnable lobbyAction) {
		Hosted hosted = seat.hosted();
		hosted.bindings.computeIfPresent(seat.playerId(), (playerId, bound) -> {
			if (!bound.equals(sessionId)) {
				return bound;
			}
			hosted.players.remove(playerId, seat.queue());
			try {
				hosted.scheduler.execute(lobbyAction);
			}
			catch (RejectedExecutionException e) {
				// The lobby closed meanwhile and freed its seats; there is no lobby left to tell.
				log.debug("session {} unbound after its lobby closed", sessionId);
			}
			return null;
		});
	}

	// rejoin{token}: takes the token's seat on this session. A session still bound to it is
	// replaced: its seat is released, its queue stopped and its socket closed with 4001.
	private void rejoin(String sessionId, JsonNode node) {
		JsonNode tokenNode = node.path("token");
		if (!tokenNode.isString()) {
			sendError(sessionId, "bad-message");
			return;
		}
		if (seats.containsKey(sessionId)) {
			sendError(sessionId, "already-in-room");
			return;
		}
		String token = tokenNode.stringValue();
		Login login = logins.get(token);
		if (login == null) {
			sendError(sessionId, "rejoin-failed");
			return;
		}
		OutboundQueue queue = queues.get(sessionId);
		if (queue == null) {
			return;
		}
		Hosted hosted = login.hosted();
		Seat seat = new Seat(hosted, login.playerId(), token, queue);
		if (seats.putIfAbsent(sessionId, seat) != null) {
			sendError(sessionId, "already-in-room");
			return;
		}
		String[] previous = new String[1];
		boolean[] submitted = new boolean[1];
		hosted.bindings.compute(login.playerId(), (playerId, bound) -> {
			// Before the task is submitted, so the reconnect's joined and snapshot reach this queue.
			hosted.players.put(playerId, queue);
			try {
				hosted.scheduler.execute(() -> reconnected(sessionId, seat));
			}
			catch (RejectedExecutionException e) {
				hosted.players.remove(playerId, queue);
				return bound;
			}
			submitted[0] = true;
			previous[0] = bound;
			return sessionId;
		});
		if (!submitted[0]) {
			// The lobby closed after the token lookup.
			seats.remove(sessionId, seat);
			sendError(sessionId, "rejoin-failed");
			return;
		}
		if (previous[0] != null && !previous[0].equals(sessionId)) {
			replace(previous[0], seat);
		}
	}

	// On the lobby thread, after the rebinding: a refused reconnect (grace over, or the member is
	// gone) undoes this session's binding, seat and queue entry and drops the token.
	private void reconnected(String sessionId, Seat seat) {
		Hosted hosted = seat.hosted();
		if (hosted.lobby.reconnect(seat.playerId())) {
			return;
		}
		hosted.bindings.remove(seat.playerId(), sessionId);
		seats.remove(sessionId, seat);
		hosted.players.remove(seat.playerId(), seat.queue());
		logins.remove(seat.token());
		sendError(sessionId, "rejoin-failed");
	}

	// The session the player was bound to before a rejoin: its seat is released and its queue
	// stopped, then its socket is closed with 4001. Its later close callback finds no seat (or a
	// binding that moved) and does nothing for the player.
	private void replace(String oldSessionId, Seat next) {
		Seat old = seats.get(oldSessionId);
		if (old != null && old.hosted() == next.hosted() && old.playerId().equals(next.playerId())) {
			seats.remove(oldSessionId, old);
			old.queue().stop();
			// Already overwritten by the rejoin's put; conditional, so the new queue stays.
			next.hosted().players.remove(old.playerId(), old.queue());
		}
		WebSocketSession session = sessions.get(oldSessionId);
		if (session == null) {
			return;
		}
		try {
			session.close(REPLACED);
		}
		catch (IOException e) {
			log.warn("session {} replaced, close failed: {}", oldSessionId, e.getClass().getName());
		}
	}

	// 128 random bits, base64url without padding (22 characters).
	private String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		codes.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// Runs the action on the session's lobby thread, or answers no-room.
	private void onLobby(String sessionId, Consumer<Seat> action) {
		Seat seat = seats.get(sessionId);
		if (seat == null) {
			sendError(sessionId, "no-room");
			return;
		}
		try {
			seat.hosted().scheduler.execute(() -> action.accept(seat));
		}
		catch (RejectedExecutionException e) {
			sendError(sessionId, "no-room");
		}
	}

	// Removes this seat only (never a newer seat of the same session), its binding, its queue
	// entry and its token.
	private void release(String sessionId, Seat seat) {
		seats.remove(sessionId, seat);
		seat.hosted().bindings.remove(seat.playerId(), sessionId);
		seat.hosted().players.remove(seat.playerId(), seat.queue());
		logins.remove(seat.token());
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
		offer(queue, sessionId, message);
	}

	private static void offer(OutboundQueue queue, String sessionId, Map<String, Object> message) {
		try {
			queue.offer(MAPPER.writeValueAsString(message));
		}
		catch (JacksonException e) {
			log.error("session {} reply not serialized: {}", sessionId, e.getClass().getName());
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
