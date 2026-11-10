package com.songrush.game;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import com.songrush.cover.CoverMap;

// One room's game as a server-side state machine: LOBBY -> (PREPARE -> PLAYING -> REVEAL) per song
// -> LOBBY (docs/cycles/036-room-state-machine.md). Not thread-safe: every method and every
// scheduled task runs on the room's single thread. Holds answers: nothing that identifies the
// current song leaves the room before its reveal message. The one exception (D-111, cycle 100 A9):
// in a choice game the k cover ids of a song's choices leave in its prepare (and the snapshot),
// in shuffled order and with nothing that marks the correct one.
// With prefetchOnReveal (D-119, docs/cycles/107-prefetch-on-reveal.md) the next song's prepare goes out
// when a reveal starts: the room is then in PREPARE for the next song while revealing, and the song
// starts only after the reveal pause, as before.
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

	static final int MAX_CHAT = ChatRules.MAX_CHAT;

	// A choice game needs this many covered series groups in its pool (cycle 100 A5).
	static final int MIN_CHOICE_GROUPS = 4;

	// Choices per song by tier (cycle 100 item 3): easy, normal (no tier), hard.
	static final int EASY_CHOICES = 4;

	static final int NORMAL_CHOICES = 6;

	static final int HARD_CHOICES = 8;

	// Never fewer choices than this (cycle 100 item 3).
	static final int MIN_CHOICES = 2;

	// Ranked points by rank (cycle 101 item 3): 3 for 1st, 2 for 2nd, then 1.
	static final int FIRST_RANK_POINTS = 3;

	static final int SECOND_RANK_POINTS = 2;

	static final int LATER_RANK_POINTS = 1;

	// Covers an early prepare's reveal pause too (cycle 107 item 7).
	private static final Duration TOKEN_TTL = REVEAL_PAUSE.plus(PREPARE_TIMEOUT).plus(LEAD).plus(MAX_PLAY)
			.plus(TOKEN_SLACK);

	private static final List<String> TITLE_KEYS = List.of("ko", "en", "ja", "romaji");

	private final Catalog catalog;

	private final AudioTokens tokens;

	private final GameScheduler scheduler;

	private final Clock clock;

	private final RandomGenerator random;

	private final Outbox outbox;

	// Cover ids leave the room only in a reveal's coverUrl (cycle 093), and in a choice game in the
	// choices of a prepare and the snapshot (cycle 100 A9).
	private final CoverMap covers;

	// Room players in player order; late joiners are appended when promoted.
	private final List<PlayerRef> players;

	private final Map<String, PlayerRef> byId = new LinkedHashMap<>();

	// Pending players (watchers) in the order added, promoted at the next prepare
	// (docs/cycles/046-late-joiners.md).
	private final Map<String, PlayerRef> watchers = new LinkedHashMap<>();

	private final Map<String, Integer> scores = new LinkedHashMap<>();

	private final Set<String> ready = new HashSet<>();

	// Room players and watchers whose connection the lobby reported as dropped
	// (docs/cycles/045-multiplayer-rounds.md); a watcher's entry counts only once promoted.
	private final Set<String> disconnected = new HashSet<>();

	// Skip votes for the current song; cleared on every prepare and reveal.
	private final Set<String> votes = new HashSet<>();

	// Chat token buckets (ChatRules), shared by chat and skip.
	private final Map<String, ChatRules.Bucket> buckets = new HashMap<>();

	private Phase phase = Phase.LOBBY;

	private int songIndex = -1;

	// Bumped on every start and pause, so a task from an earlier game, or from before a pause, never
	// acts later.
	private int generation;

	// Set by pause() while nobody is connected (docs/cycles/044-pause-empty.md): no timer runs and
	// ready and chat are ignored until resume().
	private boolean paused;

	private List<Song> songs = List.of();

	// The draw filter of the current (or last started) game; NONE before the first start.
	private DrawFilter settings = DrawFilter.NONE;

	// The answer mode of the current (or last started) game; CHAT before the first start.
	private AnswerMode answerMode = AnswerMode.CHAT;

	// The game mode of the current (or last started) game; FIRST before the first start.
	private GameMode gameMode = GameMode.FIRST;

	// In a choice game, each song's choices by song index, drawn at start; empty in a chat game.
	private List<Choices> choices = List.of();

	// The current song's picks: player id to position. Cleared only when the song index changes
	// (not on a resume's re-prepare) and at start (cycle 100 A4).
	private final Map<String, Integer> picks = new HashMap<>();

	// In a ranked game, the current song's correct players by id in rank order. Kept like picks:
	// cleared only when the song index changes and at start, never by a resume (cycle 101 R2).
	private final List<String> rankedIds = new ArrayList<>();

	private long startAt;

	private long deadlineAt;

	// The audioUrl sent in the current song's prepare.
	private String audioUrl;

	// The one task of the current phase: prepare timeout, deadline, or reveal pause. While revealing
	// it is the reveal pause, then the prepare timeout (cycle 107).
	private Cancellable pending;

	// Early mode (D-119): the next song's prepare goes out when a reveal starts. Fixed per room.
	private final boolean prefetchOnReveal;

	// Early mode only: true from an early prepare until its reveal pause ends (or a pause). The room is
	// in PREPARE for the next song; ready is recorded but starts nothing, chat is as in REVEAL.
	private boolean revealing;

	// When the current early prepare's reveal pause ends; meaningful only while revealing.
	private long revealEndsAt;

	public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, RandomGenerator random,
			Outbox outbox, List<PlayerRef> players) {
		this(catalog, tokens, scheduler, clock, random, outbox, players, CoverMap.empty());
	}

	public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, RandomGenerator random,
			Outbox outbox, List<PlayerRef> players, CoverMap covers) {
		this(catalog, tokens, scheduler, clock, random, outbox, players, covers, false);
	}

	// As above; with prefetchOnReveal the next song is prepared when a reveal starts (D-119).
	public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock, RandomGenerator random,
			Outbox outbox, List<PlayerRef> players, CoverMap covers, boolean prefetchOnReveal) {
		this.prefetchOnReveal = prefetchOnReveal;
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.tokens = Objects.requireNonNull(tokens, "tokens");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.random = Objects.requireNonNull(random, "random");
		this.outbox = Objects.requireNonNull(outbox, "outbox");
		this.covers = Objects.requireNonNull(covers, "covers");
		this.players = new ArrayList<>(players);
		if (this.players.isEmpty()) {
			throw new IllegalArgumentException("a room needs at least one player");
		}
		for (PlayerRef p : this.players) {
			if (byId.put(p.id(), p) != null) {
				throw new IllegalArgumentException("duplicate player id: " + p.id());
			}
			scores.put(p.id(), 0);
			buckets.put(p.id(), new ChatRules.Bucket(clock.millis()));
		}
	}

	// True for a promoted Room player, false for a watcher or an unknown id.
	public boolean isPlayer(String playerId) {
		return byId.containsKey(playerId);
	}

	// Adds a watcher who becomes a Room player with 0 points at the next prepare. Ignored in LOBBY and
	// for a known or already pending id. Sends nothing, except while revealing (early mode): the next
	// song is already prepared, so the player is promoted at once and alone gets its prepare with the
	// remaining reveal time as holdMs (D-059, cycle 107 item 5).
	public void addPlayer(PlayerRef player) {
		if (phase == Phase.LOBBY || byId.containsKey(player.id()) || watchers.containsKey(player.id())) {
			return;
		}
		buckets.put(player.id(), new ChatRules.Bucket(clock.millis()));
		if (revealing) {
			promote(player);
			Map<String, Object> prepare = prepareMessage(songIndex);
			prepare.put("holdMs", Math.max(0, revealEndsAt - clock.millis()));
			outbox.toPlayer(player.id(), prepare);
			return;
		}
		watchers.put(player.id(), player);
	}

	// Makes a watcher (or a joiner during an early reveal) a Room player with 0 points, last in order.
	private void promote(PlayerRef p) {
		players.add(p);
		byId.put(p.id(), p);
		scores.put(p.id(), 0);
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

	// As currentSong(), and in a choice game also the song's choices (as sent in its prepare) and
	// that player's picked position, or null (always null for a watcher). In a ranked game also
	// answered (that player answered this song correctly; false for a watcher) and correct, the
	// [{name, rank}] rows already sent in correct messages, in rank order (cycle 101 R5).
	public Map<String, Object> currentSong(String playerId) {
		Map<String, Object> song = currentSong();
		if (song == null) {
			return null;
		}
		if (answerMode == AnswerMode.CHOICE) {
			song.put("choices", choices.get(songIndex).wire());
			song.put("picked", picks.get(playerId));
		}
		if (gameMode == GameMode.RANKED) {
			song.put("answered", rankedIds.contains(playerId));
			song.put("correct", correctRows());
		}
		return song;
	}

	// The current song's ranking as [{name, rank}] in rank order: only names already sent in correct
	// messages, nothing that identifies the song.
	private List<Object> correctRows() {
		List<Object> correct = new ArrayList<>();
		for (int i = 0; i < rankedIds.size(); i++) {
			correct.add(row("name", byId.get(rankedIds.get(i)).name(), "rank", i + 1));
		}
		return correct;
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
		start(songCount, DrawFilter.NONE);
	}

	public void start(int songCount, DrawFilter filter) {
		start(songCount, filter, AnswerMode.CHAT);
	}

	// Draws songCount songs from the grouped candidates that match the filter, including its tier by
	// anime rank. Easy draws series groups uniformly and one song per group; normal and hard are a
	// uniform draw over songs.
	// Too few: everyone gets not-enough-songs with the narrowed count (easy counts series groups and
	// adds unit "series"). With a non-empty filter or the choice mode, everyone gets settings (the
	// filter's set fields, then answerMode for choice) before the first prepare
	// (docs/cycles/060-draw-filters.md, 063-tier-filter.md, 094-nested-tiers.md).
	// A choice game draws only themes whose anime has a cover, needs MIN_CHOICE_GROUPS covered series
	// groups in that pool (else not-enough-songs in groups, unit "series"), and draws every song's
	// choices at start, after the songs, so a chat game uses no more randomness than before
	// (docs/cycles/100-choice-answers.md).
	public void start(int songCount, DrawFilter filter, AnswerMode mode) {
		start(songCount, filter, mode, GameMode.FIRST);
	}

	// As start(songCount, filter, mode), with the game mode (D-112) fixed for this game; a ranked game
	// also sends settings (docs/cycles/101-game-modes.md).
	public void start(int songCount, DrawFilter filter, AnswerMode mode, GameMode game) {
		Objects.requireNonNull(filter, "filter");
		Objects.requireNonNull(mode, "mode");
		Objects.requireNonNull(game, "game");
		if (phase != Phase.LOBBY) {
			return;
		}
		if (songCount < MIN_SONGS || songCount > MAX_SONGS) {
			outbox.toAll(message("error", "code", "bad-song-count"));
			return;
		}
		boolean choice = mode == AnswerMode.CHOICE;
		List<Song> drawable = new ArrayList<>();
		for (Candidate c : catalog.candidates()) {
			catalog.theme(c.themeId())
					.flatMap(t -> catalog.anime(t.animeId()).map(a -> new Song(c, t, a)))
					.filter(s -> s.anime().group() != null)
					.filter(s -> filter.matches(s.theme(), s.anime()) && filter.matchesAnimeRank(catalog.animeRank(c.themeId())))
					.filter(s -> !choice || covers.idFor(s.anime().id()).isPresent())
					.ifPresent(drawable::add);
		}
		if (choice) {
			int groupCount = (int) drawable.stream().map(s -> s.anime().group()).distinct().count();
			if (groupCount < MIN_CHOICE_GROUPS) {
				outbox.toAll(message("error", "code", "not-enough-songs", "available", groupCount, "unit", "series"));
				return;
			}
		}
		if (DrawFilter.EASY.equals(filter.tier())) {
			// Group by series group in order of first appearance (deterministic for a seeded random),
			// shuffle the groups (Fisher-Yates), then one uniform song from each of the first songCount
			// groups: every group is equally likely, whatever its song count
			// (docs/cycles/096-easy-uniform-series.md).
			Map<Integer, List<Song>> byGroup = new LinkedHashMap<>();
			for (Song s : drawable) {
				byGroup.computeIfAbsent(s.anime().group(), g -> new ArrayList<>()).add(s);
			}
			List<List<Song>> groups = new ArrayList<>(byGroup.values());
			if (groups.size() < songCount) {
				outbox.toAll(message("error", "code", "not-enough-songs", "available", groups.size(), "unit", "series"));
				return;
			}
			for (int i = 0; i < groups.size(); i++) {
				Collections.swap(groups, i, random.nextInt(i, groups.size()));
			}
			List<Song> picked = new ArrayList<>();
			for (List<Song> group : groups.subList(0, songCount)) {
				picked.add(group.get(random.nextInt(group.size())));
			}
			songs = List.copyOf(picked);
		}
		else {
			if (drawable.size() < songCount) {
				outbox.toAll(message("error", "code", "not-enough-songs", "available", drawable.size()));
				return;
			}
			// Partial Fisher-Yates: the first songCount entries are a uniform draw without repeats.
			for (int i = 0; i < songCount; i++) {
				Collections.swap(drawable, i, random.nextInt(i, drawable.size()));
			}
			songs = List.copyOf(drawable.subList(0, songCount));
		}
		choices = choice ? drawChoices(drawable, filter) : List.of();
		picks.clear();
		rankedIds.clear();
		settings = filter;
		answerMode = mode;
		gameMode = game;
		scores.replaceAll((id, points) -> 0);
		generation++;
		Map<String, Object> settingsMessage = settingsMessage();
		if (settingsMessage != null) {
			outbox.toAll(settingsMessage);
		}
		prepare(0);
	}

	// The settings message of this game: the filter's set fields, then answerMode for a choice game
	// (left out for chat), then gameMode for a ranked game (left out for first); null when there is
	// nothing to tell (no filter, chat, first).
	Map<String, Object> settingsMessage() {
		if (settings.isEmpty() && answerMode != AnswerMode.CHOICE && gameMode != GameMode.RANKED) {
			return null;
		}
		Map<String, Object> message = message("settings");
		message.putAll(settings.toMessageFields());
		if (answerMode == AnswerMode.CHOICE) {
			message.put("answerMode", answerMode.wire());
		}
		if (gameMode == GameMode.RANKED) {
			message.put("gameMode", gameMode.wire());
		}
		return message;
	}

	// Each drawn song's choices, from the game's covered pool (cycle 100 item 3, A2, A5, O3): the
	// song's anime and up to k - 1 distractors, each from a different series group other than the
	// song's own (earlier answers' groups may appear, O3), so k = min(tier k, covered groups in the
	// pool). Shuffled (Fisher-Yates); the correct position is kept.
	private List<Choices> drawChoices(List<Song> pool, DrawFilter filter) {
		boolean easy = DrawFilter.EASY.equals(filter.tier());
		int k = easy ? EASY_CHOICES : DrawFilter.HARD.equals(filter.tier()) ? HARD_CHOICES : NORMAL_CHOICES;
		List<Choices> out = new ArrayList<>();
		for (Song song : songs) {
			int group = song.anime().group();
			List<Song> eligible = new ArrayList<>();
			for (Song s : pool) {
				if (s.anime().group() != group) {
					eligible.add(s);
				}
			}
			List<Anime> shown = new ArrayList<>();
			shown.add(song.anime());
			shown.addAll(easy ? distractorsByGroup(eligible, k - 1) : distractorsBySong(eligible, k - 1));
			for (int i = 0; i < shown.size(); i++) {
				Collections.swap(shown, i, random.nextInt(i, shown.size()));
			}
			List<Object> wire = new ArrayList<>();
			for (Anime a : shown) {
				wire.add(Collections.unmodifiableMap(row("coverUrl", "/cover/" + covers.idFor(a.id()).orElseThrow())));
			}
			out.add(new Choices(Collections.unmodifiableList(wire), shown.indexOf(song.anime())));
		}
		return List.copyOf(out);
	}

	// Easy's draw (D-108): a uniformly drawn remaining group, then a uniform song in it; up to n.
	private List<Anime> distractorsByGroup(List<Song> eligible, int n) {
		Map<Integer, List<Song>> byGroup = new LinkedHashMap<>();
		for (Song s : eligible) {
			byGroup.computeIfAbsent(s.anime().group(), g -> new ArrayList<>()).add(s);
		}
		List<List<Song>> groups = new ArrayList<>(byGroup.values());
		int count = Math.min(n, groups.size());
		List<Anime> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Collections.swap(groups, i, random.nextInt(i, groups.size()));
			List<Song> group = groups.get(i);
			out.add(group.get(random.nextInt(group.size())).anime());
		}
		return out;
	}

	// Normal's and hard's draw: a uniform song among those whose group is not yet shown; up to n.
	private List<Anime> distractorsBySong(List<Song> eligible, int n) {
		List<Song> left = new ArrayList<>(eligible);
		List<Anime> out = new ArrayList<>();
		while (out.size() < n && !left.isEmpty()) {
			Anime a = left.get(random.nextInt(left.size())).anime();
			out.add(a);
			int group = a.group();
			left.removeIf(other -> other.anime().group() == group);
		}
		return out;
	}

	// The draw filter of this game (NONE when started without one).
	public DrawFilter settings() {
		return settings;
	}

	public AnswerMode answerMode() {
		return answerMode;
	}

	public GameMode gameMode() {
		return gameMode;
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
		// A pause ends an early prepare's reveal: resume re-prepares the song (cycle 107 item 6).
		revealing = false;
	}

	// From PREPARE or PLAYING, prepares the same song again (new token, ready set cleared, no holdMs,
	// also after an early prepare); from REVEAL, continues as if the reveal pause had just ended. Does
	// nothing when not paused.
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
		if (paused || phase != Phase.PREPARE || index != songIndex || !isConnected(playerId)) {
			return;
		}
		ready.add(playerId);
		startIfAllReady();
	}

	// The lobby's report of a Room player's connection; an unknown id or no change is ignored. A
	// disconnect drops the player's ready mark and skip vote, then the ready and skip rules run again
	// over the players still connected. A watcher's state is only recorded for its promotion.
	public void setConnected(String playerId, boolean connected) {
		if (watchers.containsKey(playerId)) {
			if (connected) {
				disconnected.remove(playerId);
			}
			else {
				disconnected.add(playerId);
			}
			return;
		}
		if (!byId.containsKey(playerId) || connected == isConnected(playerId)) {
			return;
		}
		boolean hadVotes = !votes.isEmpty();
		if (connected) {
			disconnected.remove(playerId);
		}
		else {
			disconnected.add(playerId);
			ready.remove(playerId);
			votes.remove(playerId);
		}
		if (paused) {
			return;
		}
		if (phase == Phase.PREPARE) {
			startIfAllReady();
		}
		else if (phase == Phase.PLAYING) {
			if (hadVotes) {
				sendSkipStateAndPass();
			}
			if (!connected) {
				// Ranked: the song does not wait for a player who left (cycle 101 item 6).
				endIfAllAnswered();
			}
		}
	}

	// Removes the player's skip vote, if any, and tells everyone the new count.
	public void clearSkipVote(String playerId) {
		if (votes.remove(playerId)) {
			sendSkipStateAndPass();
		}
	}

	// Toggles the player's vote to skip the current song (D-012, D-017); from PLAYING only, also
	// before startAt. A vote costs a chat token; a refused vote is tagged for:"skipVote" so the
	// client can undo its toggle (cycle 050).
	public void skip(String playerId, int index) {
		if (paused || phase != Phase.PLAYING || index != songIndex || !isConnected(playerId)) {
			return;
		}
		if (!takeToken(playerId)) {
			outbox.toPlayer(playerId, message("error", "code", "rate-limited", "for", "skipVote"));
			return;
		}
		if (!votes.remove(playerId)) {
			votes.add(playerId);
		}
		sendSkipStateAndPass();
	}

	// Answers the current song by choice position in a choice game (cycle 100 item 5, A4, A11). In
	// order: not a choice game, paused, not PLAYING, not a connected Room player (a watcher), another
	// index or a position out of range send nothing; at or after the deadline the song ends with no
	// winner and nothing more; before startAt the sender gets too-early; a second pick of the song gets
	// already-picked. A correct pick wins as a correct chat does (without a chat line); a wrong one
	// locks the player out of picks for the song and tells the sender only. A pick costs no chat token.
	// In a ranked game a correct pick is ranked and scored instead and the song goes on; the song ends
	// once every connected Room player has picked, right or wrong (cycle 101 item 4).
	public void pick(String playerId, int index, int choice) {
		if (answerMode != AnswerMode.CHOICE || paused || phase != Phase.PLAYING || !isConnected(playerId)
				|| index != songIndex) {
			return;
		}
		Choices current = choices.get(songIndex);
		if (choice < 0 || choice >= current.wire().size()) {
			return;
		}
		long now = clock.millis();
		if (now >= deadlineAt) {
			endSong(null);
			return;
		}
		if (now < startAt) {
			outbox.toPlayer(playerId, message("error", "code", "too-early", "for", "pick"));
			return;
		}
		if (picks.containsKey(playerId)) {
			outbox.toPlayer(playerId, message("error", "code", "already-picked", "for", "pick"));
			return;
		}
		picks.put(playerId, choice);
		if (gameMode == GameMode.RANKED) {
			if (choice == current.correct()) {
				rankCorrect(byId.get(playerId));
			}
			else {
				outbox.toPlayer(playerId, message("pick-result", "index", songIndex, "choice", choice));
			}
			endIfAllAnswered();
			return;
		}
		if (choice != current.correct()) {
			outbox.toPlayer(playerId, message("pick-result", "index", songIndex, "choice", choice));
			return;
		}
		PlayerRef player = byId.get(playerId);
		scores.merge(playerId, 1, Integer::sum);
		outbox.toAll(message("correct", "index", songIndex, "name", player.name()));
		endSong(player);
	}

	public void chat(String playerId, String text) {
		PlayerRef player = byId.containsKey(playerId) ? byId.get(playerId) : watchers.get(playerId);
		if (paused || player == null) {
			return;
		}
		ChatRules.Verdict verdict = ChatRules.check(buckets.get(playerId), clock.millis(), text);
		if (verdict != ChatRules.Verdict.PASS) {
			if (verdict.errorCode() != null) {
				outbox.toPlayer(playerId, message("error", "code", verdict.errorCode()));
			}
			return;
		}
		// A text of only punctuation or symbols normalizes to empty: an ordinary chat line that never matches (D-100).
		String normalized = Normalize.normalize(text);
		long now = clock.millis();
		// Read before the deadline check below: a reveal that this very chat starts sends the next
		// prepare only now, so the sender cannot have heard song N + 1 (no spoiler guard for it).
		boolean revealedBefore = revealing;
		if (phase == Phase.PLAYING && now >= deadlineAt) {
			endSong(null);
		}
		Map<String, Object> chat = message("chat", "name", player.name(), "text", text);
		// While revealing (early mode) chat is as in REVEAL: broadcast, never answer-checked, also when
		// the endSong just above started the reveal (cycle 107 item 3, review M2). One exception (D-120):
		// in a reveal already running when the chat arrived, the next song's audio is reachable, so a line
		// matching song songIndex's (N + 1) accepted titles goes to its sender only. It never scores.
		if (revealing) {
			boolean spoiler = revealedBefore && !normalized.isEmpty()
					&& catalog.accepted(songs.get(songIndex).anime().group()).contains(normalized);
			if (spoiler) {
				outbox.toPlayer(playerId, chat);
			}
			else {
				outbox.toAll(chat);
			}
			return;
		}
		if (phase != Phase.PREPARE && phase != Phase.PLAYING) {
			outbox.toAll(chat);
			return;
		}
		if (watchers.containsKey(playerId)) {
			// A watcher cannot score: until the reveal only the sender sees it, match or miss.
			outbox.toPlayer(playerId, chat);
			return;
		}
		Song song = songs.get(songIndex);
		// In a choice game chat never scores (cycle 100 O4): every line takes the wrong-guess path below.
		// In a ranked game a player who already answered this song is never answer-checked: their
		// lines are ordinary chat, spoilers included (cycle 101 item 3).
		boolean match = answerMode != AnswerMode.CHOICE && !normalized.isEmpty()
				&& !(gameMode == GameMode.RANKED && rankedIds.contains(playerId))
				&& catalog.accepted(song.anime().group()).contains(normalized);
		if (phase == Phase.PREPARE || now < startAt) {
			// Too early: match or miss, only the sender sees it, so nothing tells whether it matched.
			outbox.toPlayer(playerId, chat);
		}
		else if (!match) {
			outbox.toAll(chat);
		}
		else if (gameMode == GameMode.RANKED) {
			rankCorrect(player);
			// The song goes on, so the correct text reaches only its sender, right after correct (R3).
			outbox.toPlayer(playerId, chat);
			endIfAllAnswered();
		}
		else {
			scores.merge(playerId, 1, Integer::sum);
			outbox.toAll(message("correct", "index", songIndex, "name", player.name()));
			// The round is decided by the frame above, so the winning text is shown to all (D-094).
			outbox.toAll(chat);
			endSong(player);
		}
	}

	private boolean isConnected(String playerId) {
		return byId.containsKey(playerId) && !disconnected.contains(playerId);
	}

	private int connectedCount() {
		int connected = 0;
		for (PlayerRef p : players) {
			if (!disconnected.contains(p.id())) {
				connected++;
			}
		}
		return connected;
	}

	// Ranked: the player gets the next rank of this song and its points; everyone gets
	// correct{index, name, rank}, which names nothing of the song.
	private void rankCorrect(PlayerRef player) {
		rankedIds.add(player.id());
		int rank = rankedIds.size();
		scores.merge(player.id(), points(rank), Integer::sum);
		outbox.toAll(message("correct", "index", songIndex, "name", player.name(), "rank", rank));
	}

	private static int points(int rank) {
		return rank == 1 ? FIRST_RANK_POINTS : rank == 2 ? SECOND_RANK_POINTS : LATER_RANK_POINTS;
	}

	// Ranked, in PLAYING: ends the song once every connected Room player has answered correctly (chat)
	// or picked (choice), never with nobody connected (cycle 101 R1).
	private void endIfAllAnswered() {
		if (gameMode != GameMode.RANKED || phase != Phase.PLAYING) {
			return;
		}
		int connected = 0;
		for (PlayerRef p : players) {
			if (disconnected.contains(p.id())) {
				continue;
			}
			connected++;
			boolean answered = answerMode == AnswerMode.CHOICE ? picks.containsKey(p.id()) : rankedIds.contains(p.id());
			if (!answered) {
				return;
			}
		}
		if (connected > 0) {
			endSong(null);
		}
	}

	// In PREPARE: starts the song once every connected player is ready (never with nobody connected).
	// Does nothing while revealing: the reveal's end checks again (cycle 107 item 2).
	private void startIfAllReady() {
		if (revealing) {
			return;
		}
		int connected = connectedCount();
		if (connected > 0 && ready.size() == connected) {
			startSong();
		}
	}

	// Sends everyone skipState; ends the song with no winner when the votes reach a majority of the
	// connected players.
	private void sendSkipStateAndPass() {
		int needed = connectedCount() / 2 + 1;
		outbox.toAll(message("skipState", "index", songIndex, "votes", votes.size(), "needed", needed));
		if (votes.size() >= needed) {
			endSong(null);
		}
	}

	// Spends one token of the player's bucket after the refill; false without one (the caller
	// tells the sender).
	private boolean takeToken(String playerId) {
		return buckets.get(playerId).take(clock.millis());
	}

	private void prepare(int index) {
		outbox.toAll(enterPrepare(index));
		schedule(PREPARE_TIMEOUT, this::startSong);
	}

	// Everything a prepare does but sending and scheduling: enters PREPARE for that index, promotes the
	// watchers, clears ready and votes and issues the token. Returns the prepare message.
	private Map<String, Object> enterPrepare(int index) {
		phase = Phase.PREPARE;
		songIndex = index;
		for (PlayerRef p : watchers.values()) {
			promote(p);
		}
		watchers.clear();
		ready.clear();
		votes.clear();
		String token = tokens.issue(songs.get(index).theme().id(), TOKEN_TTL);
		audioUrl = "/audio/" + token;
		return prepareMessage(index);
	}

	// The current song's prepare message as everyone gets it, with the audioUrl already issued.
	private Map<String, Object> prepareMessage(int index) {
		Map<String, Object> prepare = message("prepare", "index", index, "total", songs.size(), "audioUrl", audioUrl);
		if (answerMode == AnswerMode.CHOICE) {
			prepare.put("choices", choices.get(index).wire());
		}
		// Ranked: a resume's re-prepare of a song that already has a ranking tells everyone who answered,
		// so a player who answered before everyone left knows it (cycle 101 review round 2 C2).
		if (gameMode == GameMode.RANKED && !rankedIds.isEmpty()) {
			prepare.put("correct", correctRows());
		}
		return prepare;
	}

	// Early mode: the reveal pause of an early prepare is over. Starts the song if every connected
	// player is already ready, else waits for them up to PREPARE_TIMEOUT, as a prepare does.
	private void revealOver() {
		revealing = false;
		int connected = connectedCount();
		if (connected > 0 && ready.size() == connected) {
			startSong();
			return;
		}
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
		// Ranked: a resumed song whose connected players have all answered ends at once (cycle 101 R6).
		endIfAllAnswered();
	}

	// Ends the song with that winner; in a ranked game always with the rank-1 player (or null) and the
	// ranking [{name, rank, points}], whatever ended it (cycle 101 R7).
	private void endSong(PlayerRef winner) {
		phase = Phase.REVEAL;
		votes.clear();
		boolean ranked = gameMode == GameMode.RANKED;
		if (ranked) {
			winner = rankedIds.isEmpty() ? null : byId.get(rankedIds.get(0));
		}
		Song song = songs.get(songIndex);
		Map<String, List<String>> titles = new LinkedHashMap<>();
		for (String key : TITLE_KEYS) {
			titles.put(key, song.anime().titles().getOrDefault(key, List.of()));
		}
		Map<String, Object> reveal = message("reveal", "index", songIndex, "reveal", song.anime().reveal(), "titles", titles,
				"song", song.theme().song(), "artists", song.theme().artists(), "themeType", song.theme().type(),
				"themeSlug", song.theme().slug(), "winner", winner == null ? null : winner.name(), "coverUrl",
				covers.idFor(song.anime().id()).map(id -> "/cover/" + id).orElse(null));
		if (answerMode == AnswerMode.CHOICE) {
			reveal.put("correctChoice", choices.get(songIndex).correct());
		}
		if (ranked) {
			List<Object> ranking = new ArrayList<>();
			for (int i = 0; i < rankedIds.size(); i++) {
				ranking.add(row("name", byId.get(rankedIds.get(i)).name(), "rank", i + 1, "points", points(i + 1)));
			}
			reveal.put("ranking", ranking);
		}
		outbox.toAll(reveal);
		List<Object> rows = new ArrayList<>();
		for (PlayerRef p : players) {
			rows.add(row("name", p.name(), "score", scores.get(p.id())));
		}
		outbox.toAll(message("scores", "scores", rows));
		if (prefetchOnReveal && songIndex + 1 < songs.size()) {
			// Early mode (cycle 107 item 2): the next song's prepare right after reveal and scores, held
			// on clients for the reveal pause; the song starts only after it.
			picks.clear();
			rankedIds.clear();
			Map<String, Object> prepare = enterPrepare(songIndex + 1);
			prepare.put("holdMs", REVEAL_PAUSE.toMillis());
			revealing = true;
			revealEndsAt = clock.millis() + REVEAL_PAUSE.toMillis();
			outbox.toAll(prepare);
			schedule(REVEAL_PAUSE, this::revealOver);
			return;
		}
		schedule(REVEAL_PAUSE, this::afterReveal);
	}

	private void afterReveal() {
		picks.clear();
		rankedIds.clear();
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

	// One song's choices: the {coverUrl} rows by position, as sent, and the correct position.
	private record Choices(List<Object> wire, int correct) {

		// Never prints the correct position.
		@Override
		public String toString() {
			return "Choices[size=" + wire.size() + "]";
		}

	}

	private record Song(Candidate candidate, Theme theme, Anime anime) {

		// Ids only: the theme and anime are answers.
		@Override
		public String toString() {
			return "Song[themeId=" + theme.id() + "]";
		}

	}

}
