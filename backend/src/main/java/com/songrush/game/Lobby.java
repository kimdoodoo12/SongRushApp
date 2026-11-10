package com.songrush.game;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.cover.CoverMap;

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

	// The room theme ids, the eight moods (D-122), exact and case-sensitive; DEFAULT_THEME at creation.
	static final Set<String> ROOM_THEMES = Set.of("sunset", "rain", "sakura", "neon", "chalk", "pixel", "matsuri",
			"manga");

	static final String DEFAULT_THEME = "sunset";

	// The ids before cycle 109, still sent by tabs opened before the release; stored as the mapped id.
	static final Map<String, String> OLD_THEMES = Map.of("play", "sunset", "star", "rain", "arcade", "pixel");

	private final String code;

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final GameScheduler scheduler;

	private final Clock clock;

	private final RandomGenerator random;

	private final Outbox outbox;

	private final Runnable onClosed;

	// Passed to every Room; only a reveal names a cover, and in a choice game the choices (cycle 100 A9).
	private final CoverMap covers;

	// Passed to every Room: the next song is prepared when a reveal starts (D-119, cycle 107).
	private final boolean prefetchOnReveal;

	// Join order. In LOBBY a disconnected member is one in grace; during a game a member who left
	// (or whose grace ran out) stays, disconnected, until the return.
	private final List<Member> members = new ArrayList<>();

	private boolean closed;

	// The host-picked look, kept across games (docs/cycles/071-room-themes.md).
	private String roomTheme = DEFAULT_THEME;

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
		this(code, catalog, tokens, scheduler, clock, random, outbox, onClosed, CoverMap.empty());
	}

	public Lobby(String code, Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
			RandomGenerator random, Outbox outbox, Runnable onClosed, CoverMap covers) {
		this(code, catalog, tokens, scheduler, clock, random, outbox, onClosed, covers, false);
	}

	public Lobby(String code, Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
			RandomGenerator random, Outbox outbox, Runnable onClosed, CoverMap covers, boolean prefetchOnReveal) {
		this.prefetchOnReveal = prefetchOnReveal;
		this.code = Objects.requireNonNull(code, "code");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.random = Objects.requireNonNull(random, "random");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
		this.onClosed = Objects.requireNonNull(onClosed, "onClosed");
		this.covers = Objects.requireNonNull(covers, "covers");
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

		// The lobby chat's token bucket (ChatRules), created full at join; a Room keeps its own.
		final ChatRules.Bucket chatBucket;

		Member(String id, String name, long nowMs) {
			this.id = id;
			this.name = name;
			this.chatBucket = new ChatRules.Bucket(nowMs);
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
		members.add(new Member(playerId, name, clock.millis()));
		cancelEmpty();
		armIdle();
		Member m = members.get(members.size() - 1);
		if (room == null) {
			outbox.toPlayer(playerId, message("joined", "code", code, "name", name));
			sendRoster();
			sendRoomThemeTo(m);
		}
		else {
			// A late joiner watches the current song and plays from the next one (D-059).
			sendJoinedAndSnapshot(m);
			sendRoster();
			sendRoomThemeTo(m);
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
			sendRoomThemeTo(m);
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
		sendRoomThemeTo(m);
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
		start(playerId, songCount, DrawFilter.NONE);
	}

	// As start(playerId, songCount), with the game's draw limited by the filter.
	public void start(String playerId, int songCount, DrawFilter filter) {
		start(playerId, songCount, filter, AnswerMode.CHAT);
	}

	// As start(playerId, songCount, filter), with the game's answer mode (D-111), fixed for that game.
	public void start(String playerId, int songCount, DrawFilter filter, AnswerMode mode) {
		start(playerId, songCount, filter, mode, GameMode.FIRST);
	}

	// As start(playerId, songCount, filter, mode), with the game's game mode (D-112), fixed for that game.
	public void start(String playerId, int songCount, DrawFilter filter, AnswerMode mode, GameMode gameMode) {
		Objects.requireNonNull(filter, "filter");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(gameMode, "gameMode");
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
		Room next = new Room(catalog, tokens, scheduler, clock, random, wrapper, players, covers, prefetchOnReveal);
		room = next;
		roomOutbox = wrapper;
		next.start(songCount, filter, mode, gameMode);
		if (next.phase() == Room.Phase.LOBBY) {
			// The Room sent its error (not-enough-songs) and never started.
			room = null;
			roomOutbox = null;
			armIdle();
			return;
		}
		sendRoster();
	}

	// The host changes the room theme in any phase; every connected member gets roomTheme, also when
	// the value is unchanged. A bad id is reported before the host check (cycle 071, review nits). An
	// old id is mapped first, so only a current id is stored and broadcast (cycle 109).
	public void setRoomTheme(String playerId, String requested) {
		Member m = member(playerId);
		if (closed || m == null || !m.connected) {
			return;
		}
		String theme = requested == null ? null : OLD_THEMES.getOrDefault(requested, requested);
		if (theme == null || !ROOM_THEMES.contains(theme)) {
			error(playerId, "bad-room-theme");
			return;
		}
		if (!playerId.equals(host())) {
			error(playerId, "not-host");
			return;
		}
		roomTheme = theme;
		toConnected(roomThemeMessage());
	}

	public void ready(String playerId, int index) {
		if (forwards(playerId)) {
			room.ready(playerId, index);
		}
	}

	// During a game the Room handles chat. Between games a connected member's line, under the shared
	// ChatRules, goes to every connected member, sender included, and is never answer-checked
	// (docs/cycles/113-lobby-chat.md).
	public void chat(String playerId, String text) {
		if (forwards(playerId)) {
			room.chat(playerId, text);
			return;
		}
		Member m = member(playerId);
		if (closed || room != null || m == null || !m.connected) {
			return;
		}
		ChatRules.Verdict verdict = ChatRules.check(m.chatBucket, clock.millis(), text);
		if (verdict != ChatRules.Verdict.PASS) {
			if (verdict.errorCode() != null) {
				error(playerId, verdict.errorCode());
			}
			return;
		}
		toConnected(message("chat", "name", m.name, "text", text));
	}

	public void skip(String playerId, int index) {
		if (forwards(playerId)) {
			room.skip(playerId, index);
		}
	}

	public void pick(String playerId, int index, int choice) {
		if (forwards(playerId)) {
			room.pick(playerId, index, choice);
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
	// carries a player id, a token or a catalog field: rows as in roster, the Room's currentSong for
	// that player (already-sent prepare and start values, and in a choice game the choices and the
	// player's own pick, in a ranked game that player's answered and the correct names) and the Room's
	// scores by name. During a game with a non-empty draw filter, the choice mode or the ranked mode,
	// settings follows the snapshot: the one place every joiner, rejoiner and replaced socket gets it
	// (cycle 060, review H2; cycle 100 A8; cycle 101).
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
				room == null ? null : room.currentSong(m.id), "scores", scores));
		Map<String, Object> settings = room == null ? null : room.settingsMessage();
		if (settings != null) {
			outbox.toPlayer(m.id, settings);
		}
	}

	// roomTheme to that player only, and only off the default, so default lobbies send what they did
	// before cycle 071.
	private void sendRoomThemeTo(Member m) {
		if (!DEFAULT_THEME.equals(roomTheme)) {
			outbox.toPlayer(m.id, roomThemeMessage());
		}
	}

	private Map<String, Object> roomThemeMessage() {
		return message("roomTheme", "theme", roomTheme);
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
