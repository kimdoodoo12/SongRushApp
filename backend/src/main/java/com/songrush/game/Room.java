package com.songrush.game;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Candidate;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;

// One room's game as a server-side state machine: LOBBY -> (PREPARE -> PLAYING -> REVEAL) per song
// -> LOBBY (docs/cycles/036-room-state-machine.md). Not thread-safe: every method and every
// scheduled task runs on the room's single thread. Holds answers: nothing that identifies the
// current song leaves the room before its reveal message.
public final class Room {

	public enum Phase {
		LOBBY, PREPARE, PLAYING, REVEAL
	}

	static final Duration PREPARE_TIMEOUT = Duration.ofSeconds(25);

	static final Duration LEAD = Duration.ofSeconds(3);

	static final Duration MAX_PLAY = Duration.ofSeconds(90);

	static final Duration REVEAL_PAUSE = Duration.ofSeconds(5);

	static final Duration TOKEN_SLACK = Duration.ofSeconds(30);

	static final int MIN_SONGS = 5;

	static final int MAX_SONGS = 30;

	static final int MAX_CHAT = 200;

	private static final Duration TOKEN_TTL = PREPARE_TIMEOUT.plus(LEAD).plus(MAX_PLAY).plus(TOKEN_SLACK);

	private static final List<String> TITLE_KEYS = List.of("ko", "en", "ja", "romaji");

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final GameScheduler scheduler;

	private final Clock clock;

	private final RandomGenerator random;

	private final Outbox outbox;

	private final List<PlayerRef> players;

	private final Map<String, PlayerRef> byId = new LinkedHashMap<>();

	private final Map<String, Integer> scores = new LinkedHashMap<>();

	private final Set<String> ready = new HashSet<>();

	private Phase phase = Phase.LOBBY;

	private int songIndex = -1;

	// Bumped on every start and pause, so a task from an earlier game, or from before a pause, never
	// acts later.
	private int generation;

	// Set by pause() while nobody is connected (docs/cycles/044-pause-empty.md): no timer runs and
	// ready and chat are ignored until resume().
	private boolean paused;

	private List<Song> songs = List.of();

	private long startAt;

	private long deadlineAt;

	// The audioUrl sent in the current song's prepare.
	private String audioUrl;

	// The one task of the current phase: prepare timeout, deadline, or reveal pause.
	private Cancellable pending;

	public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, RandomGenerator random,
			Outbox outbox, List<PlayerRef> players) {
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.random = Objects.requireNonNull(random, "random");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
		this.players = List.copyOf(players);
		if (this.players.isEmpty()) {
			throw new IllegalArgumentException("a room needs at least one player");
		}
		for (PlayerRef p : this.players) {
			if (byId.put(p.id(), p) != null) {
				throw new IllegalArgumentException("duplicate player id: " + p.id());
			}
			scores.put(p.id(), 0);
		}
	}

	public Phase phase() {
		return phase;
	}

	public int songIndex() {
		return songIndex;
	}

	// Player id to points, in player order.
	public Map<String, Integer> scores() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(scores));
	}

	// The current song as a reconnecting player needs it: null unless PREPARE or PLAYING, else
	// {index, total, audioUrl}, plus startAt and deadlineAt in PLAYING. Only values already sent in
	// that song's prepare and start messages, never anything that identifies the song.
	public Map<String, Object> currentSong() {
		if (paused || (phase != Phase.PREPARE && phase != Phase.PLAYING)) {
			return null;
		}
		Map<String, Object> song = row("index", songIndex, "total", songs.size(), "audioUrl", audioUrl);
		if (phase == Phase.PLAYING) {
			song.put("startAt", startAt);
			song.put("deadlineAt", deadlineAt);
		}
		return song;
	}

	public void start(int songCount) {
		if (phase != Phase.LOBBY) {
			return;
		}
		if (songCount < MIN_SONGS || songCount > MAX_SONGS) {
			outbox.toAll(message("error", "code", "bad-song-count"));
			return;
		}
		List<Song> drawable = new ArrayList<>();
		for (Candidate c : catalog.candidates()) {
			catalog.theme(c.themeId())
					.flatMap(t -> catalog.anime(t.animeId()).map(a -> new Song(c, t, a)))
					.filter(s -> s.anime().group() != null)
					.ifPresent(drawable::add);
		}
		if (drawable.size() < songCount) {
			outbox.toAll(message("error", "code", "not-enough-songs"));
			return;
		}
		// Partial Fisher-Yates: the first songCount entries are a uniform draw without repeats.
		for (int i = 0; i < songCount; i++) {
			Collections.swap(drawable, i, random.nextInt(i, drawable.size()));
		}
		songs = List.copyOf(drawable.subList(0, songCount));
		scores.replaceAll((id, points) -> 0);
		generation++;
		prepare(0);
	}

	public boolean paused() {
		return paused;
	}

	// Stops the game in place: the phase and song index stay, the pending task is cancelled and made
	// stale, and nothing is sent. Does nothing in LOBBY or when already paused.
	public void pause() {
		if (phase == Phase.LOBBY || paused) {
			return;
		}
		if (pending != null) {
			pending.cancel();
			pending = null;
		}
		generation++;
		paused = true;
	}

	// From PREPARE or PLAYING, prepares the same song again (new token, ready set cleared); from
	// REVEAL, continues as if the reveal pause had just ended. Does nothing when not paused.
	public void resume() {
		if (!paused) {
			return;
		}
		paused = false;
		if (phase == Phase.REVEAL) {
			afterReveal();
		}
		else {
			prepare(songIndex);
		}
	}

	public void ready(String playerId, int index) {
		if (paused || phase != Phase.PREPARE || index != songIndex || !byId.containsKey(playerId)) {
			return;
		}
		ready.add(playerId);
		if (ready.size() == players.size()) {
			startSong();
		}
	}

	public void chat(String playerId, String text) {
		PlayerRef player = byId.get(playerId);
		if (paused || player == null || text == null) {
			return;
		}
		if (text.length() > MAX_CHAT) {
			outbox.toPlayer(playerId, message("error", "code", "too-long"));
			return;
		}
		String normalized = Normalize.normalize(text);
		if (normalized.isEmpty()) {
			return;
		}
		long now = clock.millis();
		if (phase == Phase.PLAYING && now >= deadlineAt) {
			endSong(null);
		}
		Map<String, Object> chat = message("chat", "name", player.name(), "text", text);
		if (phase != Phase.PREPARE && phase != Phase.PLAYING) {
			outbox.toAll(chat);
			return;
		}
		Song song = songs.get(songIndex);
		boolean match = catalog.accepted(song.anime().group()).contains(normalized);
		if (!match) {
			outbox.toAll(chat);
		}
		else if (phase == Phase.PLAYING && now >= startAt) {
			scores.merge(playerId, 1, Integer::sum);
			outbox.toAll(message("correct", "index", songIndex, "name", player.name()));
			endSong(player);
		}
		else {
			// Too early: looks to the sender exactly like a miss, and no one else sees it.
			outbox.toPlayer(playerId, chat);
		}
	}

	private void prepare(int index) {
		phase = Phase.PREPARE;
		songIndex = index;
		ready.clear();
		String token = tokens.issue(songs.get(index).theme().id(), TOKEN_TTL);
		audioUrl = "/audio/" + token;
		outbox.toAll(message("prepare", "index", index, "total", songs.size(), "audioUrl", audioUrl));
		schedule(PREPARE_TIMEOUT, this::startSong);
	}

	private void startSong() {
		phase = Phase.PLAYING;
		long now = clock.millis();
		startAt = now + LEAD.toMillis();
		deadlineAt = startAt + Math.min(songs.get(songIndex).candidate().durationMs(), MAX_PLAY.toMillis());
		outbox.toAll(message("start", "index", songIndex, "startAt", startAt, "deadlineAt", deadlineAt,
				"serverTime", now));
		schedule(Duration.ofMillis(deadlineAt - now), () -> endSong(null));
	}

	private void endSong(PlayerRef winner) {
		phase = Phase.REVEAL;
		Song song = songs.get(songIndex);
		Map<String, List<String>> titles = new LinkedHashMap<>();
		for (String key : TITLE_KEYS) {
			titles.put(key, song.anime().titles().getOrDefault(key, List.of()));
		}
		outbox.toAll(message("reveal", "index", songIndex, "reveal", song.anime().reveal(), "titles", titles,
				"song", song.theme().song(), "artists", song.theme().artists(), "themeType", song.theme().type(),
				"themeSlug", song.theme().slug(), "winner", winner == null ? null : winner.name()));
		List<Object> rows = new ArrayList<>();
		for (PlayerRef p : players) {
			rows.add(row("name", p.name(), "score", scores.get(p.id())));
		}
		outbox.toAll(message("scores", "scores", rows));
		schedule(REVEAL_PAUSE, this::afterReveal);
	}

	private void afterReveal() {
		if (songIndex + 1 < songs.size()) {
			prepare(songIndex + 1);
			return;
		}
		pending = null;
		phase = Phase.LOBBY;
		songIndex = -1;
		songs = List.of();
		outbox.toAll(message("gameOver", "ranking", ranking()));
	}

	// Points descending, ties in player order, standard competition ranks (3, 3, 1 -> 1, 1, 3).
	private List<Object> ranking() {
		List<PlayerRef> order = new ArrayList<>(players);
		order.sort((a, b) -> Integer.compare(scores.get(b.id()), scores.get(a.id())));
		List<Object> rows = new ArrayList<>();
		int rank = 0;
		int previous = -1;
		for (int i = 0; i < order.size(); i++) {
			PlayerRef p = order.get(i);
			int score = scores.get(p.id());
			if (i == 0 || score != previous) {
				rank = i + 1;
			}
			previous = score;
			rows.add(row("rank", rank, "name", p.name(), "score", score));
		}
		return rows;
	}

	// Replaces the current phase's task. The task does nothing unless the game, song index and
	// phase are still the ones it was scheduled for.
	private void schedule(Duration delay, Runnable action) {
		if (pending != null) {
			pending.cancel();
		}
		int gen = generation;
		int index = songIndex;
		Phase at = phase;
		pending = scheduler.schedule(delay, () -> {
			if (gen == generation && index == songIndex && at == phase) {
				action.run();
			}
		});
	}

	// A message: "type" first, then the keys and values in order.
	private static Map<String, Object> message(String type, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		m.putAll(row(keysAndValues));
		return m;
	}

	// A plain map of the keys and values in order (a list row inside a message).
	private static Map<String, Object> row(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	private record Song(Candidate candidate, Theme theme, Anime anime) {

		// Ids only: the theme and anime are answers.
		@Override
		public String toString() {
			return "Song[themeId=" + theme.id() + "]";
		}

	}

}
