package com.songrush.game;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;

// A room code's players between games: join, leave, host, start, the return after gameOver, and
// the cleanup timers (docs/cycles/040-rooms-lobby.md), and the 30 s grace period after a dropped
// connection (docs/cycles/042-reconnect.md). Each game is a fresh Room built from the
// players connected at start. Not thread-safe: every method, every timer and every Room of the
// lobby runs on the scheduler's single thread. Every message goes out as one toPlayer per
// connected player in join order; the lobby never calls toAll (spec review H1).
public final class Lobby {

	public static final int MAX_PLAYERS = 8;

	// During a game: members of any state (connected, in grace, left or expired) the lobby holds.
	static final int MAX_GAME_MEMBERS = 2 * MAX_PLAYERS;

	public static final Duration EMPTY_TTL = Duration.ofMinutes(2);

	public static final Duration IDLE_TTL = Duration.ofMinutes(10);

	public static final Duration GRACE = Duration.ofSeconds(30);

	static final int MAX_NICKNAME = 16;

	private final String code;

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final GameScheduler scheduler;

	private final Clock clock;

	private final RandomGenerator random;

	private final Outbox outbox;

	private final Runnable onClosed;

	// Join order. In LOBBY a disconnected member is one in grace; during a game a member who left
	// (or whose grace ran out) stays, disconnected, until the return.
	private final List<Member> members = new ArrayList<>();

	private boolean closed;

	// The current game, or null in LOBBY, and the outbox only that Room may send through.
	private Room room;

	private RoomOutbox roomOutbox;

	// Bumped on every arm and cancel, so a stale timer does nothing.
	private int emptyGeneration;

	private int idleGeneration;

	private Cancellable emptyTimer;

	private Cancellable idleTimer;

	public Lobby(String code, Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
			RandomGenerator random, Outbox outbox, Runnable onClosed) {
		this.code = Objects.requireNonNull(code, "code");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.random = Objects.requireNonNull(random, "random");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
		this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
		armIdle();
	}

	private static final class Member {

		final String id;

		final String name;

		boolean connected = true;

		// Set by disconnect; cleared by the grace expiry, leave or reconnect.
		boolean inGrace;

		// Bumped on every grace start and end, so a stale grace timer does nothing.
		int graceGeneration;

		Cancellable graceTimer;

		// The score of the last finished game (0 before the first).
		int score;

		Member(String id, String name) {
			this.id = id;
			this.name = name;
		}

	}

	public Room.Phase phase() {
		return room == null ? Room.Phase.LOBBY : room.phase();
	}

	// The earliest-joined connected player, or null.
	public String host() {
		for (Member m : members) {
			if (m.connected) {
				return m.id;
			}
		}
		return null;
	}

	public List<String> names() {
		List<String> names = new ArrayList<>();
		for (Member m : members) {
			names.add(m.name);
		}
		return names;
	}

	public boolean closed() {
		return closed;
	}

	// The assigned name, or null after sending the joiner an error.
	public String join(String playerId, String nickname) {
		Objects.requireNonNull(playerId, "playerId");
		if (closed) {
			error(playerId, "room-not-found");
			return null;
		}
		String trimmed = nickname == null ? "" : nickname.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_NICKNAME) {
			error(playerId, "bad-nickname");
			return null;
		}
		// During a game members who left stay until the return, so the member list itself is also
		// capped: otherwise one socket repeating join and leave would grow it without bound.
		if (seatsTaken() >= MAX_PLAYERS || (room != null && members.size() >= MAX_GAME_MEMBERS)) {
			error(playerId, "room-full");
			return null;
		}
		if (member(playerId) != null) {
			error(playerId, "already-in-room");
			return null;
		}
		String name = freeName(trimmed);
		members.add(new Member(playerId, name));
		cancelEmpty();
		armIdle();
		Member m = members.get(members.size() - 1);
		if (room == null) {
			outbox.toPlayer(playerId, message("joined", "code", code, "name", name));
			sendRoster();
		}
		else {
			// A late joiner watches the current song and plays from the next one (D-059).
			sendJoinedAndSnapshot(m);
			sendRoster();
			room.addPlayer(new PlayerRef(m.id, m.name));
		}
		return name;
	}

	// The members holding a seat: in LOBBY all of them, during a game those connected or in grace
	// (the others wait only for their removal at the return).
	private int seatsTaken() {
		if (room == null) {
			return members.size();
		}
		int taken = 0;
		for (Member m : members) {
			if (m.connected || m.inGrace) {
				taken++;
			}
		}
		return taken;
	}

	public void leave(String playerId) {
		Member m = member(playerId);
		if (closed || m == null) {
			return;
		}
		if (m.inGrace) {
			// Already disconnected: leaving now is the grace running out early.
			expireGrace(m);
			return;
		}
		if (!m.connected) {
			return;
		}
		if (room == null) {
			members.remove(m);
			armIdle();
		}
		else {
			m.connected = false;
		}
		sendRoster();
		if (host() == null) {
			armEmpty();
		}
		markRoomConnection(m.id, false);
		pauseIfNoRoomPlayer();
		abandonIfNobodyCanResume();
	}

	// A dropped connection: the member keeps the seat for GRACE, then is treated as having left.
	public void disconnect(String playerId) {
		Member m = member(playerId);
		if (closed || m == null || !m.connected) {
			return;
		}
		m.connected = false;
		m.inGrace = true;
		m.graceGeneration++;
		int gen = m.graceGeneration;
		m.graceTimer = scheduler.schedule(GRACE, () -> {
			if (!closed && m.inGrace && gen == m.graceGeneration) {
				expireGrace(m);
			}
		});
		sendRoster();
		if (host() == null) {
			armEmpty();
		}
		markRoomConnection(m.id, false);
		pauseIfNoRoomPlayer();
	}

	// True for a member in grace (now connected again) or a connected member (a replaced socket:
	// no state or roster change); either way that player gets joined and then snapshot. False, with
	// nothing sent, for anyone else.
	public boolean reconnect(String playerId) {
		Member m = member(playerId);
		if (closed || m == null) {
			return false;
		}
		if (m.connected) {
			sendJoinedAndSnapshot(m);
			// The new socket starts with no vote, so a toggle never cancels a vote it cannot see.
			if (isRoomPlayer(m.id)) {
				room.clearSkipVote(m.id);
			}
			return true;
		}
		if (!m.inGrace) {
			return false;
		}
		endGrace(m);
		m.connected = true;
		cancelEmpty();
		sendJoinedAndSnapshot(m);
		sendRoster();
		markRoomConnection(m.id, true);
		if (isRoomPlayer(m.id)) {
			room.clearSkipVote(m.id);
			if (room.paused()) {
				room.resume();
			}
		}
		else if (room != null) {
			// In grace at start (a spectator): plays from the next song, like a late joiner.
			room.addPlayer(new PlayerRef(m.id, m.name));
		}
		return true;
	}

	public void start(String playerId, int songCount) {
		Member m = member(playerId);
		if (closed || m == null || !m.connected) {
			return;
		}
		if (room != null) {
			error(playerId, "in-game");
			return;
		}
		if (!playerId.equals(host())) {
			error(playerId, "not-host");
			return;
		}
		if (songCount < Room.MIN_SONGS || songCount > Room.MAX_SONGS) {
			error(playerId, "bad-song-count");
			return;
		}
		List<PlayerRef> players = new ArrayList<>();
		for (Member p : members) {
			if (p.connected) {
				players.add(new PlayerRef(p.id, p.name));
			}
		}
		RoomOutbox wrapper = new RoomOutbox();
		Room next = new Room(catalog, tokens, scheduler, clock, random, wrapper, players);
		room = next;
		roomOutbox = wrapper;
		next.start(songCount);
		if (next.phase() == Room.Phase.LOBBY) {
			// The Room sent its error (not-enough-songs) and never started.
			room = null;
			roomOutbox = null;
			armIdle();
			return;
		}
		sendRoster();
	}

	public void ready(String playerId, int index) {
		if (forwards(playerId)) {
			room.ready(playerId, index);
		}
	}

	public void chat(String playerId, String text) {
		if (forwards(playerId)) {
			room.chat(playerId, text);
		}
	}

	public void skip(String playerId, int index) {
		if (forwards(playerId)) {
			room.skip(playerId, index);
		}
	}

	private boolean forwards(String playerId) {
		Member m = member(playerId);
		return !closed && room != null && m != null && m.connected;
	}

	// Called by the current Room's outbox right after gameOver is delivered.
	private void returnToLobby() {
		Map<String, Integer> scores = room.scores();
		for (Member m : members) {
			m.score = scores.getOrDefault(m.id, 0);
		}
		// Members still in grace stay, and their grace timer keeps running (spec review M1).
		members.removeIf(m -> !m.connected && !m.inGrace);
		room = null;
		roomOutbox = null;
		armIdle();
		sendRoster();
	}

	private void close() {
		if (closed) {
			return;
		}
		closed = true;
		room = null;
		roomOutbox = null;
		cancelEmpty();
		cancelIdle();
		for (Member m : members) {
			endGrace(m);
		}
		Map<String, Object> error = message("error", "code", "room-closed");
		for (Member m : members) {
			if (m.connected) {
				outbox.toPlayer(m.id, error);
			}
		}
		onClosed.run();
	}

	// A Room player of the current game; members not (yet) promoted neither keep it running nor
	// resume it (cycle 044).
	private boolean isRoomPlayer(String playerId) {
		return room != null && room.isPlayer(playerId);
	}

	// Tells the current Room that a member's connection changed; the Room ignores ids it does not
	// know and only records a watcher's state for its promotion.
	private void markRoomConnection(String playerId, boolean connected) {
		if (room != null) {
			room.setConnected(playerId, connected);
		}
	}

	// During a game with no connected Room player, the Room stops using up songs until one returns.
	private void pauseIfNoRoomPlayer() {
		if (room == null) {
			return;
		}
		for (Member m : members) {
			if (m.connected && isRoomPlayer(m.id)) {
				return;
			}
		}
		room.pause();
	}

	// A paused game with no Room player connected or in grace can never resume: drop the Room
	// (nothing more from it is delivered), remove the members whose grace expired and return to
	// LOBBY without a gameOver. An already running empty timer is left as it is (cycle 044).
	private void abandonIfNobodyCanResume() {
		if (room == null || !room.paused()) {
			return;
		}
		for (Member m : members) {
			if ((m.connected || m.inGrace) && isRoomPlayer(m.id)) {
				return;
			}
		}
		members.removeIf(m -> !m.connected && !m.inGrace);
		room = null;
		roomOutbox = null;
		armIdle();
		sendRoster();
	}

	// The grace ran out (or the member left during it): in LOBBY they are removed, in a game they
	// stay disconnected until the return, unless that abandons a paused game. Re-arms neither the
	// empty nor the idle timer (except the abandon's idle timer): the empty timer runs from the last
	// disconnect.
	private void expireGrace(Member m) {
		endGrace(m);
		if (room == null) {
			members.remove(m);
			sendRoster();
		}
		else {
			abandonIfNobodyCanResume();
		}
	}

	private void endGrace(Member m) {
		m.inGrace = false;
		m.graceGeneration++;
		if (m.graceTimer != null) {
			m.graceTimer.cancel();
			m.graceTimer = null;
		}
	}

	private void armEmpty() {
		cancelEmpty();
		int gen = emptyGeneration;
		emptyTimer = scheduler.schedule(EMPTY_TTL, () -> {
			if (!closed && gen == emptyGeneration) {
				close();
			}
		});
	}

	private void cancelEmpty() {
		emptyGeneration++;
		if (emptyTimer != null) {
			emptyTimer.cancel();
			emptyTimer = null;
		}
	}

	private void armIdle() {
		cancelIdle();
		int gen = idleGeneration;
		idleTimer = scheduler.schedule(IDLE_TTL, () -> {
			if (!closed && gen == idleGeneration && room == null) {
				close();
			}
		});
	}

	private void cancelIdle() {
		idleGeneration++;
		if (idleTimer != null) {
			idleTimer.cancel();
			idleTimer = null;
		}
	}

	private Member member(String playerId) {
		for (Member m : members) {
			if (m.id.equals(playerId)) {
				return m;
			}
		}
		return null;
	}

	// The name itself if no present player has it, else the first free "name (k)" from k = 2 (D-048).
	private String freeName(String name) {
		List<String> taken = names();
		if (!taken.contains(name)) {
			return name;
		}
		for (int k = 2;; k++) {
			String candidate = name + " (" + k + ")";
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
	}

	private void sendRoster() {
		toConnected(message("roster", "phase", phaseName(), "players", playerRows()));
	}

	// joined{code, name} and then the allow-listed snapshot, to that player only. The snapshot never
	// carries a player id, a token or a catalog field: rows as in roster, the Room's currentSong()
	// (already-sent prepare and start values) and the Room's scores by name.
	private void sendJoinedAndSnapshot(Member m) {
		outbox.toPlayer(m.id, message("joined", "code", code, "name", m.name));
		List<Object> scores = new ArrayList<>();
		if (room != null) {
			for (Map.Entry<String, Integer> e : room.scores().entrySet()) {
				Member p = member(e.getKey());
				if (p != null) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("name", p.name);
					row.put("score", e.getValue());
					scores.add(row);
				}
			}
		}
		outbox.toPlayer(m.id, message("snapshot", "phase", phaseName(), "players", playerRows(), "song",
				room == null ? null : room.currentSong(), "scores", scores));
	}

	private String phaseName() {
		return room == null ? "lobby" : "playing";
	}

	// One roster row per member in join order: name, score, host, connected.
	private List<Object> playerRows() {
		Map<String, Integer> live = room == null ? null : room.scores();
		List<Object> players = new ArrayList<>();
		String host = host();
		for (Member m : members) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", m.name);
			row.put("score", live == null ? m.score : live.getOrDefault(m.id, 0));
			row.put("host", m.id.equals(host));
			row.put("connected", m.connected);
			players.add(row);
		}
		return players;
	}

	private void toConnected(Map<String, Object> message) {
		for (Member m : List.copyOf(members)) {
			if (m.connected) {
				outbox.toPlayer(m.id, message);
			}
		}
	}

	private void error(String playerId, String errorCode) {
		outbox.toPlayer(playerId, message("error", "code", errorCode));
	}

	// A message: "type" first, then the keys and values in order.
	private static Map<String, Object> message(String type, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	// One Room's outbox: delivers only while that Room is the lobby's current one and the lobby is
	// open, so a dropped Room's uncancellable timers stay silent (spec review M1).
	private final class RoomOutbox implements Outbox {

		private boolean live() {
			return !closed && roomOutbox == this;
		}

		@Override
		public void toPlayer(String playerId, Map<String, Object> message) {
			Member m = member(playerId);
			if (live() && m != null && m.connected) {
				outbox.toPlayer(playerId, message);
			}
		}

		@Override
		public void toAll(Map<String, Object> message) {
			if (!live()) {
				return;
			}
			toConnected(message);
			if (message != null && "gameOver".equals(message.get("type"))) {
				returnToLobby();
			}
		}

	}

}
