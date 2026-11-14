package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 128 (B-036, D-129) at Room level, on the cycle 036 fakes: the snapshot in the reveal window
// behind the switch playToLimit (docs/cycles/128-window-snapshot.md, criteria 1 to 4 and the
// switch-off null of criterion 6).
//
// No new API: currentSong(playerId) gains, exactly while a skip in the window is possible, the keys
// reveal (the broadcast reveal frame without its type key) and skipState {votes, needed}.
//
// Built like RoomWindowSkipTest, with its helpers copied here. Every song lasts at least 90 s, so
// deadlineAt = startAt + 90 s, and "decided at 10 s" is 10 s after startAt. Three players (Mina,
// Joon, Hana), so needed is 2. First come, Mina's correct chat or pick decides; ranked, Mina and
// Joon answer correctly at 4 s and Hana's correct chat (chat) or wrong pick (choice) decides.
//
// The room sends through a wrapper that records every frame in the RecordingOutbox (which copies)
// and also keeps the very reveal maps the room passed to toAll, so the test can see whether the
// room later changed the map it broadcast (the stored reveal must be a copy).
class RoomWindowSnapshotTest {

	enum Switch {
		ON, OFF_NEW, OFF_OLD
	}

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final String UNKNOWN = "p-unknown";

	private static final long[] LONG_SONGS = {90000, 120000, 95000, 100000, 110000, 130000, 91000};

	private static final long CHOICE_DURATION_MS = 120000;

	private static final long SEED = 128;

	private static final int SONGS = 5;

	private static final long MAX_PLAY_MS = Room.MAX_PLAY.toMillis();

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	private static final long EARLY_MS = 4000;

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	// The reveal maps exactly as the room passed them to toAll (not copies).
	private final List<Map<String, Object>> rawReveals = new ArrayList<>();

	private final Outbox wire = new Outbox() {

		@Override
		public void toPlayer(String playerId, Map<String, Object> message) {
			outbox.toPlayer(playerId, message);
		}

		@Override
		public void toAll(Map<String, Object> message) {
			if ("reveal".equals(message.get("type"))) {
				rawReveals.add(message);
			}
			outbox.toAll(message);
		}

	};

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private AnswerMode answer;

	private GameMode mode;

	private int index;

	private String title;

	private int correct;

	private int wrong;

	// ---- building ----

	private void build(AnswerMode answerMode, boolean prefetch, Switch sw, PlayerRef... members) {
		answer = answerMode;
		players = List.of(members);
		CoverMap covers;
		if (answer == AnswerMode.CHOICE) {
			built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, 30));
			catalog = longChoiceCatalog();
			covers = built.covers();
		}
		else {
			catalog = GameFixture.build(dir, LONG_SONGS, 0);
			covers = CoverMap.empty();
		}
		Random random = new Random(SEED);
		if (sw == Switch.OFF_OLD) {
			if (prefetch) {
				room = new Room(catalog, tokens, scheduler, clock, random, wire, players, covers, true);
			}
			else if (answer == AnswerMode.CHOICE) {
				room = new Room(catalog, tokens, scheduler, clock, random, wire, players, covers);
			}
			else {
				room = new Room(catalog, tokens, scheduler, clock, random, wire, players);
			}
		}
		else {
			room = new Room(catalog, tokens, scheduler, clock, random, wire, players, covers, prefetch, sw == Switch.ON);
		}
	}

	private void buildOn(AnswerMode answerMode, boolean prefetch) {
		build(answerMode, prefetch, Switch.ON, MINA, JOON, HANA);
	}

	private Catalog longChoiceCatalog() {
		Path indexJson = dir.resolve("audio-cache").resolve("index.json");
		try {
			String json = Files.readString(indexJson, StandardCharsets.UTF_8);
			String longer = json.replace("\"durationMs\":" + ChoiceFixture.DURATION_MS,
					"\"durationMs\":" + CHOICE_DURATION_MS);
			assertThat(longer).as("the durations were rewritten").isNotEqualTo(json);
			Files.writeString(indexJson, longer, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return CatalogLoader.load(dir.resolve("catalog.json"), indexJson);
	}

	// ---- helpers ----

	private static long num(Map<String, Object> message, String key) {
		Object value = message.get(key);
		assertThat(value).as(key).isInstanceOf(Number.class);
		return ((Number) value).longValue();
	}

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	private long startAt() {
		return num(lastStart(), "startAt");
	}

	private long deadlineAt() {
		return num(lastStart(), "deadlineAt");
	}

	private int themeOf(Map<String, Object> prepare) {
		String url = (String) prepare.get("audioUrl");
		assertThat(url).startsWith("/audio/");
		return tokens.resolve(url.substring("/audio/".length())).orElseThrow();
	}

	private String chatTitleOf(int themeId) {
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow().titles().get("en").get(0);
	}

	@SuppressWarnings("unchecked")
	private static List<String> coverUrls(Map<String, Object> carrier) {
		assertThat(carrier).containsKey("choices");
		List<String> urls = new ArrayList<>();
		for (Object choice : (List<Object>) carrier.get("choices")) {
			urls.add((String) ((Map<String, Object>) choice).get("coverUrl"));
		}
		return urls;
	}

	private void capture() {
		Map<String, Object> prepare = outbox.last("prepare").message();
		index = (int) num(prepare, "index");
		int themeId = themeOf(prepare);
		if (answer == AnswerMode.CHOICE) {
			int a = built.animeIndexOfTheme(themeId);
			title = ChoiceFixture.title(a);
			List<String> urls = coverUrls(prepare);
			correct = urls.indexOf(ChoiceFixture.coverUrl(a));
			assertThat(correct).as("the correct cover is among the choices").isNotNegative();
			wrong = (correct + 1) % urls.size();
		}
		else {
			title = chatTitleOf(themeId);
		}
	}

	private void readyAll(int songIndex) {
		for (PlayerRef p : players) {
			room.ready(p.id(), songIndex);
		}
	}

	private void play(int k) {
		capture();
		assertThat(index).isEqualTo(k);
		readyAll(k);
		assertThat(room.phase()).as("song %d playing", k).isEqualTo(Room.Phase.PLAYING);
		assertThat(deadlineAt() - startAt()).as("a song of at least 90 s").isEqualTo(MAX_PLAY_MS);
	}

	private void startGame(GameMode gameMode) {
		mode = gameMode;
		room.start(SONGS, DrawFilter.NONE, answer, gameMode);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		play(0);
	}

	private void toOffset(long ms) {
		long at = startAt() + ms;
		assertThat(at).isGreaterThanOrEqualTo(clock.millis());
		scheduler.advance(Duration.ofMillis(at - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	private void earlyAnswers() {
		for (PlayerRef p : players.subList(0, players.size() - 1)) {
			if (answer == AnswerMode.CHOICE) {
				room.pick(p.id(), index, correct);
			}
			else {
				room.chat(p.id(), title);
			}
		}
		assertThat(room.phase()).as("the last player has not answered: no reveal").isEqualTo(Room.Phase.PLAYING);
	}

	private void toDecisionPoint(long ms) {
		if (mode == GameMode.RANKED) {
			toOffset(EARLY_MS);
			earlyAnswers();
		}
		toOffset(ms);
	}

	// Decides the current song by answers, now. Returns the frames of that action.
	private List<Sent> decide() {
		int mark = outbox.mark();
		if (mode == GameMode.RANKED) {
			PlayerRef last = players.get(players.size() - 1);
			if (answer == AnswerMode.CHOICE) {
				room.pick(last.id(), index, wrong);
			}
			else {
				room.chat(last.id(), title);
			}
		}
		else if (answer == AnswerMode.CHOICE) {
			room.pick(MINA.id(), index, correct);
		}
		else {
			room.chat(MINA.id(), title);
		}
		return outbox.since(mark);
	}

	private static Map<String, Object> revealIn(List<Sent> sent) {
		List<Sent> reveals = sent.stream().filter(s -> "reveal".equals(s.type())).toList();
		assertThat(reveals).as("exactly one reveal").hasSize(1);
		assertThat(reveals.get(0).toAll()).isTrue();
		return reveals.get(0).message();
	}

	// The broadcast reveal frame as recorded at send time, without its type key.
	private static Map<String, Object> withoutType(Map<String, Object> frame) {
		Map<String, Object> copy = new LinkedHashMap<>(frame);
		assertThat(copy.remove("type")).isEqualTo("reveal");
		return copy;
	}

	// Decides the current song before its deadline with the window open; returns the recorded reveal.
	private Map<String, Object> decideOpen() {
		long deadlineAt = deadlineAt();
		assertThat(clock.millis()).as("decided before the deadline").isLessThan(deadlineAt);
		List<Sent> sent = decide();
		assertThat(types(sent)).contains("reveal", "scores", "skipState");
		Map<String, Object> reveal = revealIn(sent);
		assertThat(num(reveal, "playUntil")).isEqualTo(deadlineAt);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		return reveal;
	}

	// The per-mode keys currentSong(playerId) adds.
	private List<String> modeKeys() {
		List<String> keys = new ArrayList<>();
		if (answer == AnswerMode.CHOICE) {
			keys.addAll(List.of("choices", "picked", "pickState"));
		}
		if (mode == GameMode.RANKED) {
			keys.addAll(List.of("answered", "correct"));
		}
		return keys;
	}

	private List<String> playingKeys() {
		List<String> keys = new ArrayList<>(List.of("index", "total", "audioUrl", "startAt", "deadlineAt"));
		keys.addAll(modeKeys());
		return keys;
	}

	private List<String> windowKeys() {
		List<String> keys = playingKeys();
		keys.addAll(List.of("reveal", "skipState"));
		return keys;
	}

	private List<String> prepareKeys() {
		List<String> keys = new ArrayList<>(List.of("index", "total", "audioUrl"));
		keys.addAll(modeKeys());
		return keys;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> skipStateOf(Map<String, Object> song) {
		Object skipState = song.get("skipState");
		assertThat(skipState).as("skipState").isInstanceOf(Map.class);
		return (Map<String, Object>) skipState;
	}

	private static void assertSkipStateIn(Map<String, Object> song, int votes, int needed) {
		Map<String, Object> skipState = skipStateOf(song);
		assertThat(skipState.keySet()).as("skipState keys").containsExactlyInAnyOrder("votes", "needed");
		assertThat(num(skipState, "votes")).as("votes").isEqualTo(votes);
		assertThat(num(skipState, "needed")).as("needed").isEqualTo(needed);
	}

	// The window shape for that id: song keys, the reveal frame minus type, and skipState.
	private Map<String, Object> assertWindowSong(String playerId, Map<String, Object> reveal, int votes) {
		Map<String, Object> song = room.currentSong(playerId);
		assertThat(song).as("currentSong(%s) in the window", playerId).isNotNull();
		assertThat(song.keySet()).as("window keys").containsExactlyInAnyOrderElementsOf(windowKeys());
		assertThat(song.get("reveal")).as("reveal is the broadcast frame without type").isEqualTo(withoutType(reveal));
		assertSkipStateIn(song, votes, 2);
		return song;
	}

	private void assertRawRevealKeepsType() {
		assertThat(rawReveals).as("a reveal was broadcast").isNotEmpty();
		assertThat(rawReveals.get(rawReveals.size() - 1)).as("the broadcast reveal map still has its type")
				.containsEntry("type", "reveal");
	}

	private static void assertNoWindowKeys(Map<String, Object> song) {
		assertThat(song).as("song").isNotNull();
		assertThat(song).doesNotContainKey("reveal").doesNotContainKey("skipState");
	}

	// ---- criterion 1: the song, its reveal and the per-mode keys in the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void inTheWindowCurrentSongHasThePlayingSongTheRevealAndSkipState(AnswerMode answerMode, GameMode gameMode,
			boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		toDecisionPoint(10_000);
		Map<String, Object> playing = room.currentSong(MINA.id());
		assertThat(playing.keySet()).as("criterion 4: before the decision, the playing shape")
				.containsExactlyInAnyOrderElementsOf(playingKeys());
		assertNoWindowKeys(playing);

		Map<String, Object> reveal = decideOpen();
		if (answerMode == AnswerMode.CHOICE) {
			assertThat(reveal).containsKey("correctChoice");
		}
		if (gameMode == GameMode.RANKED) {
			assertThat(reveal).containsKey("ranking");
		}
		Map<String, Object> song = assertWindowSong(MINA.id(), reveal, 0);
		for (String key : List.of("index", "total", "audioUrl", "startAt", "deadlineAt")) {
			assertThat(song.get(key)).as("%s as while the song played", key).isEqualTo(playing.get(key));
		}
		assertThat(withoutType(reveal)).containsKey("playUntil");
		if (answerMode == AnswerMode.CHOICE) {
			assertThat(song.get("choices")).as("the choices as while the song played").isEqualTo(playing.get("choices"));
			assertThat(song.get("picked")).as("Mina's own pick is kept").isEqualTo(correct);
			assertThat(song.get("pickState")).isInstanceOf(Map.class);
		}
		if (gameMode == GameMode.RANKED) {
			assertThat(song).containsEntry("answered", true);
			assertThat(song.get("correct")).isInstanceOf(List.class);
			assertThat((List<?>) song.get("correct")).as("the ranking rows sent so far").isNotEmpty();
		}
		assertRawRevealKeepsType();

		// Asked again, later in the window: the same reveal, and the broadcast map is still untouched.
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertWindowSong(MINA.id(), reveal, 0);
		assertWindowSong(JOON.id(), reveal, 0);
		assertRawRevealKeepsType();
	}

	// ---- criterion 2: skipState in the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void skipStateInTheWindowCountsTheVotesAndTheMajority(AnswerMode answerMode, GameMode gameMode, boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		toDecisionPoint(10_000);
		Map<String, Object> reveal = decideOpen();
		assertWindowSong(MINA.id(), reveal, 0);

		scheduler.advance(Duration.ofSeconds(2));
		room.skip(JOON.id(), index);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertWindowSong(MINA.id(), reveal, 1);
		assertWindowSong(JOON.id(), reveal, 1);
		assertWindowSong(HANA.id(), reveal, 1);

		// Joon's vote toggles off: back to 0.
		room.skip(JOON.id(), index);
		assertWindowSong(HANA.id(), reveal, 0);
	}

	// ---- criterion 3: a watcher and an id the room does not know ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, RANKED, true"})
	void aWatcherAndAnUnknownIdGetTheRevealAndSkipStateToo(AnswerMode answerMode, GameMode gameMode,
			boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		toDecisionPoint(10_000);
		Map<String, Object> reveal = decideOpen();
		scheduler.advance(Duration.ofSeconds(1));
		room.addPlayer(SORA);
		assertThat(room.isPlayer(SORA.id())).isFalse();

		for (String id : List.of(SORA.id(), UNKNOWN)) {
			Map<String, Object> song = assertWindowSong(id, reveal, 0);
			if (answerMode == AnswerMode.CHOICE) {
				assertThat(song.get("picked")).as("no pick for %s", id).isNull();
			}
			if (gameMode == GameMode.RANKED) {
				assertThat(song).as("not answered for %s", id).containsEntry("answered", false);
			}
		}
		assertRawRevealKeepsType();
	}

	// ---- criterion 4: no reveal and no skipState outside the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void inPrepareThereIsNoRevealAndNoSkipState(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		mode = gameMode;
		room.start(SONGS, DrawFilter.NONE, answer, gameMode);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> song = room.currentSong(MINA.id());
		assertThat(song.keySet()).containsExactlyInAnyOrderElementsOf(prepareKeys());
		assertNoWindowKeys(song);

		// And the prepare that ends an untouched window.
		play(0);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen();
		scheduler.advance(Duration.ofMillis(deadlineAt - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		song = room.currentSong(MINA.id());
		assertThat(song.keySet()).containsExactlyInAnyOrderElementsOf(prepareKeys());
		assertNoWindowKeys(song);
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(song.get("audioUrl")).isEqualTo(outbox.last("prepare").message().get("audioUrl"));
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, RANKED, false", "CHAT, RANKED, true", "CHOICE, FIRST, true"})
	void pausedInTheWindowCurrentSongIsNull(AnswerMode answerMode, GameMode gameMode, boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen();
		scheduler.advance(Duration.ofSeconds(2));
		room.pause();
		assertThat(room.paused()).isTrue();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(MINA.id())).isNull();
		assertThat(room.currentSong(UNKNOWN)).isNull();
	}

	// No prefetch, decided 2 s before the deadline: the reveal lasts its 5 s minimum, but the window
	// ends at playUntil, the deadline.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void atOrAfterPlayUntilCurrentSongIsNull(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toDecisionPoint(MAX_PLAY_MS - 2000);
		long deadlineAt = deadlineAt();
		Map<String, Object> reveal = decideOpen();

		scheduler.advance(Duration.ofMillis(deadlineAt - 1 - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertWindowSong(MINA.id(), reveal, 0);

		scheduler.advance(Duration.ofMillis(1));
		assertThat(clock.millis()).as("exactly at playUntil").isEqualTo(deadlineAt);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(MINA.id())).isNull();
		assertThat(room.currentSong(UNKNOWN)).isNull();

		scheduler.advance(Duration.ofSeconds(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(MINA.id())).isNull();
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void afterADeadlineRevealCurrentSongIsNull(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		long deadlineAt = deadlineAt();
		scheduler.advance(Duration.ofMillis(deadlineAt - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(num(outbox.last("reveal").message(), "playUntil")).isEqualTo(deadlineAt);
		assertThat(room.currentSong(MINA.id())).isNull();
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(MINA.id())).isNull();
		assertThat(room.currentSong(UNKNOWN)).isNull();
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void afterASkipRevealCurrentSongIsNull(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(10_000);
		long skipAt = clock.millis();
		room.skip(MINA.id(), index);
		room.skip(JOON.id(), index);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(num(outbox.last("reveal").message(), "playUntil")).isEqualTo(skipAt);
		assertThat(room.currentSong(MINA.id())).isNull();
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(HANA.id())).isNull();
	}

	// Criterion 4 and the switch-off null of criterion 6: in REVEAL with the switch off, as today.
	@ParameterizedTest
	@CsvSource({"OFF_OLD, CHAT, FIRST", "OFF_OLD, CHOICE, FIRST", "OFF_OLD, CHAT, RANKED", "OFF_OLD, CHOICE, RANKED",
			"OFF_NEW, CHAT, FIRST", "OFF_NEW, CHOICE, FIRST", "OFF_NEW, CHAT, RANKED", "OFF_NEW, CHOICE, RANKED"})
	void switchOffInRevealCurrentSongIsNull(Switch sw, AnswerMode answerMode, GameMode gameMode) {
		build(answerMode, false, sw, MINA, JOON, HANA);
		startGame(gameMode);
		toDecisionPoint(10_000);
		List<Sent> sent = decide();
		assertThat(revealIn(sent)).doesNotContainKey("playUntil");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(MINA.id())).isNull();
		assertThat(room.currentSong(UNKNOWN)).isNull();
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong(JOON.id())).isNull();
	}

	// Prefetch on, decided at 10 s: in the window the reveal shape, and after the delayed prepare the
	// next song's PREPARE shape with no reveal.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void prefetchAfterTheDelayedPrepareTheSnapshotIsTheNextSongsPrepare(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, true);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		Map<String, Object> reveal = decideOpen();

		scheduler.advance(Duration.ofMillis(deadlineAt - REVEAL_MS - 1 - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertWindowSong(MINA.id(), reveal, 0);

		int mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).as("the delayed prepare").containsExactly("prepare");
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> prepare = outbox.last("prepare").message();
		for (String id : List.of(MINA.id(), UNKNOWN)) {
			Map<String, Object> song = room.currentSong(id);
			assertThat(song.keySet()).as("the PREPARE shape for %s", id).containsExactlyInAnyOrderElementsOf(prepareKeys());
			assertNoWindowKeys(song);
			assertThat(num(song, "index")).isEqualTo(1);
			assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		}
	}

}
