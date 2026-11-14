package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 126 (B-034, D-129) at Room level, on the cycle 036 fakes: the reveal window behind the
// switch playToLimit (docs/cycles/126-reveal-window.md, criteria 1 to 10).
//
// API this test pins (new; every existing constructor stays and means playToLimit = false):
//   public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
//       RandomGenerator random, Outbox outbox, List<PlayerRef> players, CoverMap covers,
//       boolean prefetchOnReveal, boolean playToLimit)
// Nothing else is new at this level: reveal gains the key playUntil when the switch is on.
//
// Every song here lasts at least 90 s, so deadlineAt = startAt + 90 s. "Decided at 10 s" is 10 s
// after startAt. A chat room uses GameFixture, a choice room ChoiceFixture with its audio index
// rewritten to 120 s songs. The current song is found by resolving the token in prepare.audioUrl,
// always right after that prepare was sent (the token expires).
// In a ranked room Mina answers first and Joon's answer (chat) or wrong pick (choice) decides the song.
class RoomPlayToLimitTest {

	// How the room under test is built: with the new constructor and the switch on, with the new
	// constructor and the switch off, or with the constructors that existed before this cycle.
	enum Switch {
		ON, OFF_NEW, OFF_OLD
	}

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final long[] LONG_SONGS = {90000, 120000, 95000, 100000, 110000, 130000, 91000};

	private static final long CHOICE_DURATION_MS = 120000;

	private static final long SEED = 126;

	private static final int SONGS = 5;

	private static final long MAX_PLAY_MS = Room.MAX_PLAY.toMillis();

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	private static final long LEAD_MS = Room.LEAD.toMillis();

	private static final long TIMEOUT_MS = Room.PREPARE_TIMEOUT.toMillis();

	// Far past any deadline, prepare timeout or reveal window of the fixture songs.
	private static final Duration LONG = Duration.ofMinutes(10);

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private AnswerMode answer;

	private GameMode mode;

	// The current song, as capture() read it from its prepare.
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
				room = new Room(catalog, tokens, scheduler, clock, random, outbox, players, covers, true);
			}
			else if (answer == AnswerMode.CHOICE) {
				room = new Room(catalog, tokens, scheduler, clock, random, outbox, players, covers);
			}
			else {
				room = new Room(catalog, tokens, scheduler, clock, random, outbox, players);
			}
		}
		else {
			room = new Room(catalog, tokens, scheduler, clock, random, outbox, players, covers, prefetch,
					sw == Switch.ON);
		}
	}

	private void buildOn(AnswerMode answerMode, boolean prefetch) {
		build(answerMode, prefetch, Switch.ON, MINA, JOON);
	}

	// The ChoiceFixture catalog loaded again with every song lasting CHOICE_DURATION_MS (the fixture's
	// own songs last 60 s, below the 90 s limit).
	private Catalog longChoiceCatalog() {
		Path indexJson = dir.resolve("audio-cache").resolve("index.json");
		try {
			String json = Files.readString(indexJson, StandardCharsets.UTF_8);
			String longer = json.replace("\"durationMs\":" + ChoiceFixture.DURATION_MS,
					"\"durationMs\":" + CHOICE_DURATION_MS);
			assertThat(longer).as("the durations were rewritten").isNotEqualTo(json)
					.doesNotContain("\"durationMs\":" + ChoiceFixture.DURATION_MS + ",")
					.doesNotContain("\"durationMs\":" + ChoiceFixture.DURATION_MS + "}");
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

	private Map<String, Object> lastPrepare() {
		return outbox.last("prepare").message();
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

	// Reads the song of the last prepare: its index, its exact title and, in a choice room, its
	// correct position and a wrong one.
	private void capture() {
		Map<String, Object> prepare = lastPrepare();
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

	// With the prepare of song k out and nobody held: reads the song and makes everyone ready.
	private void play(int k) {
		capture();
		assertThat(index).isEqualTo(k);
		readyAll(k);
		assertThat(room.phase()).as("song %d playing", k).isEqualTo(Room.Phase.PLAYING);
		assertThat(num(lastStart(), "index")).isEqualTo(k);
		assertThat(deadlineAt() - startAt()).as("a song of at least 90 s").isEqualTo(MAX_PLAY_MS);
	}

	// From LOBBY: starts a 5-song game in the room's answer format and plays song 0 until PLAYING.
	private void startGame(GameMode gameMode) {
		mode = gameMode;
		room.start(SONGS, DrawFilter.NONE, answer, gameMode);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		play(0);
	}

	// Moves the scheduler to that many milliseconds after the current song's startAt.
	private void toOffset(long ms) {
		long at = startAt() + ms;
		assertThat(at).isGreaterThanOrEqualTo(clock.millis());
		scheduler.advance(Duration.ofMillis(at - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// Ranked only: Mina answers correctly, which reveals nothing while Joon has not answered.
	private void minaAnswersFirst() {
		int mark = outbox.mark();
		if (answer == AnswerMode.CHOICE) {
			room.pick(MINA.id(), index, correct);
			assertThat(types(outbox.since(mark))).containsExactly("pickState", "correct");
		}
		else {
			room.chat(MINA.id(), title);
			assertThat(types(outbox.since(mark))).containsExactly("correct", "chat");
			assertThat(outbox.since(mark).get(1).to()).as("the correct text goes to its sender only").isEqualTo(MINA.id());
		}
		assertThat(room.phase()).as("one of two answered: no reveal").isEqualTo(Room.Phase.PLAYING);
	}

	// Decides the current song by answers, now. First come: Mina's correct chat or pick. Ranked: Mina
	// answers correctly, then Joon's correct chat or wrong pick ends it. Returns the frames of the
	// deciding action and checks they are the frames of today, in order.
	private List<Sent> decide() {
		if (mode == GameMode.RANKED) {
			minaAnswersFirst();
		}
		int mark = outbox.mark();
		if (mode == GameMode.RANKED) {
			if (answer == AnswerMode.CHOICE) {
				room.pick(JOON.id(), index, wrong);
			}
			else {
				room.chat(JOON.id(), title);
			}
		}
		else if (answer == AnswerMode.CHOICE) {
			room.pick(MINA.id(), index, correct);
		}
		else {
			room.chat(MINA.id(), title);
		}
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("the frames at the decision").containsExactlyElementsOf(decisionTypes());
		Map<String, Object> reveal = revealIn(sent);
		assertThat(num(reveal, "index")).isEqualTo(index);
		assertThat(reveal.get("winner")).isEqualTo("Mina");
		if (answer == AnswerMode.CHOICE) {
			assertThat(num(reveal, "correctChoice")).isEqualTo(correct);
		}
		if (mode == GameMode.RANKED) {
			assertThat(ranking(reveal)).as("the ranking is as today")
					.isEqualTo(answer == AnswerMode.CHOICE ? List.of("Mina:1:3") : List.of("Mina:1:3", "Joon:2:2"));
		}
		return sent;
	}

	private List<String> decisionTypes() {
		if (answer == AnswerMode.CHAT) {
			return List.of("correct", "chat", "reveal", "scores");
		}
		return mode == GameMode.RANKED ? List.of("pickState", "pick-result", "wrong", "reveal", "scores")
				: List.of("pickState", "correct", "reveal", "scores");
	}

	// The frames a song's start sends.
	private List<String> startTypes() {
		return answer == AnswerMode.CHOICE ? List.of("start", "pickState") : List.of("start");
	}

	private static Map<String, Object> revealIn(List<Sent> sent) {
		List<Sent> reveals = sent.stream().filter(s -> "reveal".equals(s.type())).toList();
		assertThat(reveals).as("exactly one reveal").hasSize(1);
		assertThat(reveals.get(0).toAll()).isTrue();
		return reveals.get(0).message();
	}

	@SuppressWarnings("unchecked")
	private static List<String> ranking(Map<String, Object> reveal) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<Object>) reveal.get("ranking")) {
			Map<String, Object> r = (Map<String, Object>) row;
			rows.add(r.get("name") + ":" + num(r, "rank") + ":" + num(r, "points"));
		}
		return rows;
	}

	// The keys of a reveal of today, in this room's answer format and game mode.
	private List<String> revealKeys(boolean withPlayUntil) {
		List<String> keys = new ArrayList<>(List.of("type", "index", "reveal", "titles", "song", "artists", "themeType",
				"themeSlug", "winner", "coverUrl"));
		if (answer == AnswerMode.CHOICE) {
			keys.add("correctChoice");
		}
		if (mode == GameMode.RANKED) {
			keys.add("ranking");
		}
		if (withPlayUntil) {
			keys.add("playUntil");
		}
		return keys;
	}

	private void assertRevealOn(Map<String, Object> reveal, long playUntil) {
		assertThat(reveal.keySet()).as("reveal keys: today's and playUntil")
				.containsExactlyInAnyOrderElementsOf(revealKeys(true));
		assertThat(num(reveal, "playUntil")).as("playUntil").isEqualTo(playUntil);
	}

	private void assertRevealOff(Map<String, Object> reveal) {
		assertThat(reveal.keySet()).as("reveal keys: no playUntil with the switch off")
				.containsExactlyInAnyOrderElementsOf(revealKeys(false));
	}

	private List<String> prepareKeys(boolean held) {
		List<String> keys = new ArrayList<>(List.of("type", "index", "total", "audioUrl"));
		if (answer == AnswerMode.CHOICE) {
			keys.add("choices");
		}
		if (held) {
			keys.add("holdMs");
		}
		return keys;
	}

	// A prepare to everyone of that song: without holdMs, or with holdMs 5000. Never a correct key.
	private void assertPrepare(Sent sent, int songIndex, boolean held) {
		assertThat(sent.type()).isEqualTo("prepare");
		assertThat(sent.toAll()).as("prepare goes to everyone").isTrue();
		Map<String, Object> prepare = sent.message();
		assertThat(prepare.keySet()).as("prepare keys").containsExactlyInAnyOrderElementsOf(prepareKeys(held));
		assertThat(num(prepare, "index")).isEqualTo(songIndex);
		assertThat(num(prepare, "total")).isEqualTo(SONGS);
		if (held) {
			assertThat(num(prepare, "holdMs")).as("holdMs").isEqualTo(REVEAL_MS);
		}
	}

	// Advances in steps of at most 1 s to 1 ms before that moment; nothing may be sent on the way.
	private void assertSilentUntilJustBefore(int mark, long at) {
		assertThat(at).as("the moment is still ahead").isGreaterThan(clock.millis());
		while (at - 1 - clock.millis() > 1000) {
			scheduler.advance(Duration.ofSeconds(1));
			assertThat(types(outbox.since(mark))).as("no frame %d ms before the moment", at - clock.millis()).isEmpty();
		}
		scheduler.advance(Duration.ofMillis(at - 1 - clock.millis()));
		assertThat(clock.millis()).isEqualTo(at - 1);
		assertThat(types(outbox.since(mark))).as("no frame 1 ms before the moment").isEmpty();
	}

	// As above, then the last millisecond: returns what was sent exactly at that moment.
	private List<Sent> sentExactlyAt(int mark, long at) {
		assertSilentUntilJustBefore(mark, at);
		scheduler.advance(Duration.ofMillis(1));
		assertThat(clock.millis()).isEqualTo(at);
		return outbox.since(mark);
	}

	// Nothing until that moment, then exactly the prepare of that song, with no holdMs.
	private void assertNextPrepareExactlyAt(int mark, long at, int songIndex) {
		List<Sent> sent = sentExactlyAt(mark, at);
		assertThat(types(sent)).as("exactly the next prepare").containsExactly("prepare");
		assertPrepare(sent.get(0), songIndex, false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(songIndex);
	}

	// From PLAYING: both players vote, the skip passes. Returns the frames of the two votes.
	private List<Sent> skipByBoth() {
		int mark = outbox.mark();
		room.skip(MINA.id(), index);
		room.skip(JOON.id(), index);
		return outbox.since(mark);
	}

	// Ends songs 0 to 3 by a passed skip, 5 s apart, and leaves the last song (4) PLAYING.
	private void skipToTheLastSong() {
		for (int k = 0; k < SONGS - 1; k++) {
			assertThat(types(skipByBoth())).contains("reveal");
			scheduler.advance(Room.REVEAL_PAUSE);
			play(k + 1);
		}
		assertThat(outbox.ofType("prepare")).hasSize(SONGS);
	}

	private long revealCount(int songIndex) {
		return outbox.ofType("reveal").stream().filter(s -> num(s.message(), "index") == songIndex).count();
	}

	// The draw order of a seeded chat game on this catalog, found by a probe room with its own clock,
	// scheduler and outbox (the draw happens at start and uses only the Random). Each prepare's token
	// is resolved within a second of its sending.
	private List<Integer> drawOrder() {
		MutableClock probeClock = new MutableClock();
		ManualScheduler probeScheduler = new ManualScheduler(probeClock);
		RecordingOutbox probeOutbox = new RecordingOutbox();
		AudioTokens probeTokens = new AudioTokens(probeClock);
		Room probe = new Room(catalog, probeTokens, probeScheduler, probeClock, new Random(SEED), probeOutbox,
				List.of(MINA, JOON));
		probe.start(SONGS);
		List<Integer> order = new ArrayList<>();
		for (int second = 0; second <= 3600; second++) {
			List<Sent> prepares = probeOutbox.ofType("prepare");
			for (Sent s : prepares.subList(order.size(), prepares.size())) {
				String url = (String) s.message().get("audioUrl");
				order.add(probeTokens.resolve(url.substring("/audio/".length())).orElseThrow());
			}
			if (!probeOutbox.ofType("gameOver").isEmpty()) {
				break;
			}
			probeScheduler.advance(Duration.ofSeconds(1));
		}
		assertThat(order).hasSize(SONGS);
		return order;
	}

	// ---- criteria 1, 4 and 5: a song decided at 10 s, no prefetch ----

	// Criterion 1 (chat, first come), 4 (choice, first come), 5 (ranked, chat and choice), 6 (one move).
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aSongDecidedAtTenSecondsKeepsItsRevealUntilTheDeadline(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		long revealAt = clock.millis();
		List<Sent> sent = decide();
		assertThat(sent.stream().filter(s -> !"chat".equals(s.type()) && !"pick-result".equals(s.type())))
				.as("everything but an own chat or pick-result goes to everyone").allMatch(Sent::toAll);
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(deadlineAt - revealAt).isEqualTo(MAX_PLAY_MS - 10_000);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isEqualTo(0);

		int mark = outbox.mark();
		// Not at reveal + 5 s, the reveal's length of today, and at no other moment before the deadline.
		assertNextPrepareExactlyAt(mark, deadlineAt, 1);
		// Exactly one move to the next song, and no second reveal of song 0.
		scheduler.advance(Room.PREPARE_TIMEOUT.minusMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// Criterion 5: the scores at the decision are those of today.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, 1, 0", "CHOICE, FIRST, 1, 0", "CHAT, RANKED, 3, 2", "CHOICE, RANKED, 3, 0"})
	void theDecisionScoresAsToday(AnswerMode answerMode, GameMode gameMode, int mina, int joon) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(10_000);
		List<Sent> sent = decide();
		assertThat(room.scores()).containsEntry(MINA.id(), mina).containsEntry(JOON.id(), joon);
		assertThat(sent.get(sent.size() - 1).message().get("scores")).isEqualTo(List.of(
				Map.of("name", "Mina", "score", mina), Map.of("name", "Joon", "score", joon)));
		scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		assertThat(room.scores()).as("the window scores nothing").containsEntry(MINA.id(), mina)
				.containsEntry(JOON.id(), joon);
	}

	// ---- criterion 2: decided 2 s before the deadline ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aSongDecidedTwoSecondsBeforeTheDeadlineStillShowsItsRevealForFiveSeconds(AnswerMode answerMode,
			GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(MAX_PLAY_MS - 2000);
		long deadlineAt = deadlineAt();
		long revealAt = clock.millis();
		assertThat(deadlineAt - revealAt).isEqualTo(2000);
		assertRevealOn(revealIn(decide()), deadlineAt);

		int mark = outbox.mark();
		// Nothing at deadlineAt (2 s on): not the next prepare, and no second reveal from the deadline task.
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// ---- criterion 3: reveals at the deadline and by a skip ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aRevealByTheDeadlineTaskAndOneByASkipHavePlayUntilNowAndLastFiveSeconds(AnswerMode answerMode,
			GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		long deadlineAt = deadlineAt();
		int mark = outbox.mark();
		List<Sent> sent = sentExactlyAt(mark, deadlineAt);
		assertThat(types(sent)).containsExactly("reveal", "scores");
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(revealIn(sent).get("winner")).isNull();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, deadlineAt + REVEAL_MS, 1);

		play(1);
		toOffset(10_000);
		long skipAt = clock.millis();
		sent = skipByBoth();
		assertThat(types(sent)).containsExactly("skipState", "skipState", "reveal", "scores");
		assertRevealOn(revealIn(sent), skipAt);
		assertThat(skipAt).as("not the deadline").isLessThan(deadlineAt());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, skipAt + REVEAL_MS, 2);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);
	}

	// The clock is set and the scheduler not advanced: the chat itself finds the deadline passed.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, 0", "CHAT, FIRST, 700", "CHOICE, FIRST, 0", "CHAT, RANKED, 0", "CHAT, RANKED, 700",
			"CHOICE, RANKED, 700"})
	void aChatAtOrAfterTheDeadlineRevealsWithPlayUntilNow(AnswerMode answerMode, GameMode gameMode, long late) {
		buildOn(answerMode, false);
		startGame(gameMode);
		long revealAt = deadlineAt() + late;
		clock.advance(Duration.ofMillis(revealAt - clock.millis()));
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("reveal", "scores", "chat");
		assertThat(sent).allMatch(Sent::toAll);
		assertRevealOn(revealIn(sent), revealAt);
		assertThat(revealIn(sent).get("winner")).isNull();
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);

		mark = outbox.mark();
		// The deadline task, already due, runs on the way and must not reveal again.
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"FIRST, 0", "FIRST, 700", "RANKED, 0", "RANKED, 700"})
	void aPickAtOrAfterTheDeadlineRevealsWithPlayUntilNow(GameMode gameMode, long late) {
		buildOn(AnswerMode.CHOICE, false);
		startGame(gameMode);
		long revealAt = deadlineAt() + late;
		clock.advance(Duration.ofMillis(revealAt - clock.millis()));
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("reveal", "scores");
		assertRevealOn(revealIn(sent), revealAt);
		assertThat(revealIn(sent).get("winner")).isNull();
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);

		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// A ranked song whose last answer comes late is still a deadline reveal: the chat finds the
	// deadline passed before it is answer-checked.
	@Test
	void rankedTheLastAnswerArrivingAfterTheDeadlineIsADeadlineReveal() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.RANKED);
		toOffset(10_000);
		minaAnswersFirst();
		long revealAt = deadlineAt() + 300;
		clock.advance(Duration.ofMillis(revealAt - clock.millis()));
		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("reveal", "scores", "chat");
		assertRevealOn(revealIn(sent), revealAt);
		assertThat(ranking(revealIn(sent))).containsExactly("Mina:1:3");
		assertThat(room.scores()).containsEntry(JOON.id(), 0);
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
	}

	// ---- criterion 5: a disconnect and a resume decide a ranked song ----

	@ParameterizedTest
	@EnumSource(AnswerMode.class)
	void rankedTheLastUnansweredPlayerDisconnectingAtTenSecondsOpensTheWindow(AnswerMode answerMode) {
		buildOn(answerMode, false);
		startGame(GameMode.RANKED);
		toOffset(4000);
		minaAnswersFirst();
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactlyElementsOf(answerMode == AnswerMode.CHOICE
				? List.of("pickState", "reveal", "scores") : List.of("reveal", "scores"));
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(ranking(revealIn(sent))).containsExactly("Mina:1:3");
		assertThat(revealIn(sent).get("winner")).isEqualTo("Mina");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, deadlineAt, 1);
	}

	// The spec's "playUntil can lie in the past": the clock passed deadlineAt, the deadline task has
	// not run yet, and the disconnect decides the song. The window is then 5 s.
	@ParameterizedTest
	@EnumSource(AnswerMode.class)
	void rankedADisconnectAfterTheDeadlineBeforeItsTaskGivesAPastPlayUntilAndAFiveSecondWindow(
			AnswerMode answerMode) {
		buildOn(answerMode, false);
		startGame(GameMode.RANKED);
		toOffset(4000);
		minaAnswersFirst();
		long deadlineAt = deadlineAt();
		long revealAt = deadlineAt + 1500;
		clock.advance(Duration.ofMillis(revealAt - clock.millis()));
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactlyElementsOf(answerMode == AnswerMode.CHOICE
				? List.of("pickState", "reveal", "scores") : List.of("reveal", "scores"));
		assertRevealOn(revealIn(sent), deadlineAt);

		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(AnswerMode.class)
	void rankedAResumedSongWhoseConnectedPlayersAllAnsweredRevealsAsItStartsUntilItsDeadline(AnswerMode answerMode) {
		buildOn(answerMode, false);
		startGame(GameMode.RANKED);
		toOffset(10_000);
		minaAnswersFirst();
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		scheduler.advance(Duration.ofSeconds(30));
		room.setConnected(MINA.id(), true);
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("reveal")).isEmpty();

		long revealAt = clock.millis();
		int mark = outbox.mark();
		room.ready(MINA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactlyElementsOf(answerMode == AnswerMode.CHOICE
				? List.of("start", "pickState", "reveal", "scores") : List.of("start", "reveal", "scores"));
		Map<String, Object> start = sent.get(0).message();
		assertThat(num(start, "serverTime")).isEqualTo(revealAt);
		assertThat(num(start, "startAt")).as("the reveal comes before startAt").isEqualTo(revealAt + LEAD_MS);
		long deadlineAt = num(start, "deadlineAt");
		assertThat(deadlineAt).as("the lead plus the whole song").isEqualTo(revealAt + LEAD_MS + MAX_PLAY_MS);
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(ranking(revealIn(sent))).containsExactly("Mina:1:3");
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, deadlineAt, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// ---- criterion 6: inside the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void inTheWindowChatIsOnlyChatAndPicksSkipsAndConnectionChangesDoNothing(AnswerMode answerMode,
			GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		Map<String, Integer> scores = room.scores();
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		room.chat(MINA.id(), title);
		List<Sent> chats = outbox.since(mark);
		assertThat(types(chats)).containsExactly("chat", "chat");
		assertThat(chats).as("broadcast, never answer-checked").allMatch(Sent::toAll);
		assertThat(chats.get(0).message()).containsEntry("name", "Joon").containsEntry("text", title);
		assertThat(chats.get(1).message()).containsEntry("name", "Mina").containsEntry("text", title);
		assertThat(room.scores()).isEqualTo(scores);

		mark = outbox.mark();
		room.pick(JOON.id(), 0, 0);
		room.pick(JOON.id(), 0, 1);
		room.pick(MINA.id(), 0, 0);
		assertThat(types(outbox.since(mark))).as("a pick is ignored").isEmpty();
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		room.skip(MINA.id(), 1);
		assertThat(types(outbox.since(mark))).as("a skip vote is ignored").isEmpty();
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		assertThat(types(outbox.since(mark))).as("a connection change sends nothing").isEmpty();
		assertThat(room.scores()).isEqualTo(scores);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isEqualTo(0);

		assertNextPrepareExactlyAt(mark, deadlineAt, 1);
		scheduler.advance(Room.PREPARE_TIMEOUT.minusMillis(1));
		assertThat(types(outbox.since(mark))).as("exactly one move to the next song").containsExactly("prepare");
		assertThat(revealCount(0)).isEqualTo(1);
		assertThat(room.scores()).isEqualTo(scores);
	}

	// The votes ignored in the window are not kept: the next song starts with none.
	@Test
	void skipVotesIgnoredInTheWindowDoNotCountForTheNextSong() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		decide();
		room.skip(MINA.id(), 0);
		room.skip(MINA.id(), 1);
		scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		play(1);
		int mark = outbox.mark();
		room.skip(JOON.id(), 1);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertThat(num(sent.get(0).message(), "votes")).isEqualTo(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- criterion 7: prefetch on ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void prefetchTheNextPrepareComesFiveSecondsBeforeTheDeadlineAndTheSongStartsNoEarlierThanIt(
			AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, true);
		startGame(gameMode);
		String song0Url = (String) lastPrepare().get("audioUrl");
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		List<Sent> sent = decide();
		assertThat(types(sent)).as("no prepare at the decision").doesNotContain("prepare");
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		int mark = outbox.mark();
		assertSilentUntilJustBefore(mark, deadlineAt - REVEAL_MS);
		assertThat(room.phase()).as("REVEAL until the delayed prepare").isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isEqualTo(0);
		Map<String, Object> before = room.currentSong(MINA.id());
		if (before != null) {
			assertThat(num(before, "index")).as("not the next song's").isEqualTo(0);
			assertThat(before.get("audioUrl")).isEqualTo(song0Url);
		}

		scheduler.advance(Duration.ofMillis(1));
		List<Sent> delayed = outbox.since(mark);
		assertThat(types(delayed)).as("exactly the next prepare").containsExactly("prepare");
		// Ranked: no correct key, so the rows of song 0 were cleared.
		assertPrepare(delayed.get(0), 1, true);
		assertThat(delayed.get(0).message().get("audioUrl")).isNotEqualTo(song0Url);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);

		Map<String, Object> song = room.currentSong(MINA.id());
		assertThat(song).isNotNull();
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(song.get("audioUrl")).isEqualTo(delayed.get(0).message().get("audioUrl"));
		if (answerMode == AnswerMode.CHOICE) {
			assertThat(song.get("picked")).as("no pick of song 0 is left").isNull();
			@SuppressWarnings("unchecked")
			Map<String, Object> pickState = (Map<String, Object>) song.get("pickState");
			assertThat(num(pickState, "picked")).isEqualTo(0);
			assertThat(num(pickState, "total")).isEqualTo(2);
		}
		if (gameMode == GameMode.RANKED) {
			assertThat(song).containsEntry("answered", false).containsEntry("correct", List.of());
		}

		// A ready after the delayed prepare starts nothing until the hold is over.
		mark = outbox.mark();
		readyAll(1);
		assertThat(types(outbox.since(mark))).as("ready during the hold starts nothing").isEmpty();
		List<Sent> started = sentExactlyAt(mark, deadlineAt);
		assertThat(types(started)).containsExactlyElementsOf(startTypes());
		Map<String, Object> start = started.get(0).message();
		assertThat(num(start, "index")).isEqualTo(1);
		assertThat(num(start, "serverTime")).isEqualTo(deadlineAt);
		assertThat(num(start, "startAt")).isEqualTo(deadlineAt + LEAD_MS);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@Test
	void prefetchAReadyForTheNextSongBeforeTheDelayedPrepareIsIgnored() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		int mark = outbox.mark();
		readyAll(1);
		readyAll(0);
		assertThat(types(outbox.since(mark))).isEmpty();
		List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
		assertThat(types(delayed)).containsExactly("prepare");
		assertPrepare(delayed.get(0), 1, true);

		// Nobody is ready for song 1: it starts at the end of the hold plus the prepare timeout.
		mark = outbox.mark();
		List<Sent> started = sentExactlyAt(mark, deadlineAt + TIMEOUT_MS);
		assertThat(types(started)).containsExactly("start");
		assertThat(num(started.get(0).message(), "serverTime")).isEqualTo(deadlineAt + TIMEOUT_MS);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void prefetchADeadlineOrSkipRevealIsFollowedByTheNextPrepareAtOnceAsToday(AnswerMode answerMode,
			GameMode gameMode) {
		buildOn(answerMode, true);
		startGame(gameMode);
		long deadlineAt = deadlineAt();
		int mark = outbox.mark();
		List<Sent> sent = sentExactlyAt(mark, deadlineAt);
		assertThat(types(sent)).containsExactly("reveal", "scores", "prepare");
		assertRevealOn(revealIn(sent), deadlineAt);
		assertPrepare(sent.get(2), 1, true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		capture();
		mark = outbox.mark();
		readyAll(1);
		List<Sent> started = sentExactlyAt(mark, deadlineAt + REVEAL_MS);
		assertThat(types(started)).containsExactlyElementsOf(startTypes());
		assertThat(num(started.get(0).message(), "index")).isEqualTo(1);

		toOffset(10_000);
		long skipAt = clock.millis();
		sent = skipByBoth();
		assertThat(types(sent)).containsExactly("skipState", "skipState", "reveal", "scores", "prepare");
		assertRevealOn(revealIn(sent), skipAt);
		assertPrepare(sent.get(4), 2, true);
		mark = outbox.mark();
		readyAll(2);
		started = sentExactlyAt(mark, skipAt + REVEAL_MS);
		assertThat(types(started)).containsExactlyElementsOf(startTypes());
		assertThat(num(started.get(0).message(), "index")).isEqualTo(2);
	}

	@Test
	void prefetchAChatAfterTheDeadlineIsFollowedByTheNextPrepareAtOnce() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		long revealAt = deadlineAt() + 700;
		clock.advance(Duration.ofMillis(revealAt - clock.millis()));
		int mark = outbox.mark();
		room.chat(MINA.id(), "too late");
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("reveal", "scores", "prepare", "chat");
		assertRevealOn(revealIn(sent), revealAt);
		assertPrepare(sent.get(2), 1, true);
		mark = outbox.mark();
		readyAll(1);
		List<Sent> started = sentExactlyAt(mark, revealAt + REVEAL_MS);
		assertThat(types(started)).containsExactly("start");
	}

	// windowEnd - 5 s is the reveal itself. The spec names the inline step only for a deadline or skip
	// reveal, so a zero-delay task is allowed here: advance(0) runs it.
	@ParameterizedTest
	@CsvSource({"2000", "5000"})
	void prefetchASongDecidedInItsLastFiveSecondsPreparesTheNextSongWithTheReveal(long beforeDeadline) {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(MAX_PLAY_MS - beforeDeadline);
		long deadlineAt = deadlineAt();
		long revealAt = clock.millis();
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		scheduler.advance(Duration.ZERO);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores", "prepare");
		assertRevealOn(revealIn(sent), deadlineAt);
		assertPrepare(sent.get(4), 1, true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		mark = outbox.mark();
		readyAll(1);
		List<Sent> started = sentExactlyAt(mark, revealAt + REVEAL_MS);
		assertThat(types(started)).containsExactly("start");
		assertThat(num(started.get(0).message(), "serverTime")).isEqualTo(revealAt + REVEAL_MS);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@Test
	void prefetchASongDecidedSevenSecondsBeforeTheDeadlinePreparesTheNextSongTwoSecondsLater() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(MAX_PLAY_MS - 7000);
		long deadlineAt = deadlineAt();
		List<Sent> sent = decide();
		assertThat(types(sent)).doesNotContain("prepare");
		int mark = outbox.mark();
		List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
		assertThat(types(delayed)).containsExactly("prepare");
		assertPrepare(delayed.get(0), 1, true);
		mark = outbox.mark();
		readyAll(1);
		assertThat(types(sentExactlyAt(mark, deadlineAt))).containsExactly("start");
	}

	// The spoiler guard (D-120) starts with the delayed prepare, not with the reveal.
	@ParameterizedTest
	@EnumSource(GameMode.class)
	void prefetchTheNextSongsTitleIsBroadcastBeforeTheDelayedPrepareAndGoesToItsSenderAfterIt(GameMode gameMode) {
		buildOn(AnswerMode.CHAT, true);
		List<Integer> order = drawOrder();
		startGame(gameMode);
		assertThat(themeOf(lastPrepare())).as("the probe matches the game").isEqualTo(order.get(0));
		String nextTitle = chatTitleOf(order.get(1));
		assertThat(nextTitle).isNotEqualTo(title);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		Map<String, Integer> scores = room.scores();
		scheduler.advance(Duration.ofSeconds(30));

		int mark = outbox.mark();
		room.chat(JOON.id(), nextTitle);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat");
		assertThat(sent.get(0).toAll()).as("before the delayed prepare: broadcast").isTrue();
		assertThat(sent.get(0).message()).containsEntry("name", "Joon").containsEntry("text", nextTitle);

		mark = outbox.mark();
		List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
		assertThat(types(delayed)).containsExactly("prepare");
		assertThat(themeOf(delayed.get(0).message())).isEqualTo(order.get(1));

		mark = outbox.mark();
		room.chat(JOON.id(), nextTitle);
		room.chat(JOON.id(), title);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat", "chat");
		assertThat(sent.get(0).to()).as("after the delayed prepare: the sender only").isEqualTo(JOON.id());
		assertThat(sent.get(0).message()).containsEntry("text", nextTitle);
		assertThat(sent.get(1).toAll()).as("the revealed song's title is ordinary chat").isTrue();
		assertThat(room.scores()).isEqualTo(scores);
	}

	@Test
	void prefetchAPlayerWhoJoinsInTheWindowIsPromotedByTheDelayedPrepare() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		scheduler.advance(Duration.ofSeconds(20));
		int mark = outbox.mark();
		room.addPlayer(HANA);
		assertThat(types(outbox.since(mark))).as("a join in the window sends nothing").isEmpty();
		assertThat(room.isPlayer(HANA.id())).isFalse();
		assertSilentUntilJustBefore(mark, deadlineAt - REVEAL_MS);
		assertThat(room.isPlayer(HANA.id())).as("still a watcher 1 ms before the delayed prepare").isFalse();

		scheduler.advance(Duration.ofMillis(1));
		List<Sent> delayed = outbox.since(mark);
		assertThat(types(delayed)).as("one prepare, to everyone, Hana included").containsExactly("prepare");
		assertPrepare(delayed.get(0), 1, true);
		assertThat(room.isPlayer(HANA.id())).isTrue();
		assertThat(room.scores()).containsEntry(HANA.id(), 0).hasSize(3);

		// Hana is waited for like any player.
		mark = outbox.mark();
		room.ready(MINA.id(), 1);
		room.ready(JOON.id(), 1);
		scheduler.advance(Duration.ofMillis(deadlineAt + 1000 - clock.millis()));
		assertThat(types(outbox.since(mark))).as("Hana is not ready").isEmpty();
		room.ready(HANA.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(deadlineAt + 1000);
	}

	// After the delayed prepare the room is "revealing" as today, and its hold ends at deadlineAt.
	@Test
	void prefetchAPlayerWhoJoinsAfterTheDelayedPrepareGetsItWithTheRestOfTheHold() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		scheduler.advance(Duration.ofMillis(deadlineAt - 3000 - clock.millis()));
		Map<String, Object> delayed = lastPrepare();
		assertThat(num(delayed, "index")).isEqualTo(1);
		int mark = outbox.mark();
		room.addPlayer(SORA);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("prepare");
		assertThat(sent.get(0).to()).isEqualTo(SORA.id());
		assertThat(num(sent.get(0).message(), "index")).isEqualTo(1);
		assertThat(num(sent.get(0).message(), "holdMs")).as("the hold ends at deadlineAt").isEqualTo(3000);
		assertThat(sent.get(0).message().get("audioUrl")).isEqualTo(delayed.get("audioUrl"));
		assertThat(room.isPlayer(SORA.id())).isTrue();
	}

	// ---- criterion 8: the last song ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void theLastSongsWindowEndsWithGameOverAndNoPrepare(AnswerMode answerMode, GameMode gameMode, boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		skipToTheLastSong();
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		List<Sent> sent = decide();
		assertRevealOn(revealIn(sent), deadlineAt);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		int mark = outbox.mark();
		List<Sent> end = sentExactlyAt(mark, deadlineAt);
		assertThat(types(end)).containsExactly("gameOver");
		assertThat(end.get(0).toAll()).isTrue();
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		scheduler.advance(LONG);
		assertThat(types(outbox.since(mark))).containsExactly("gameOver");
		assertThat(outbox.ofType("prepare")).as("no prepare after the last song").hasSize(SONGS);
		assertThat(revealCount(SONGS - 1)).isEqualTo(1);
	}

	// ---- criterion 9: everyone leaves in the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void aResumeInTheWindowMovesOnAtOnceWithAPrepareWithoutHoldMs(AnswerMode answerMode, GameMode gameMode,
			boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		scheduler.advance(Duration.ofSeconds(2));
		int mark = outbox.mark();
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		assertThat(room.paused()).isTrue();
		scheduler.advance(Duration.ofSeconds(3));
		room.setConnected(MINA.id(), true);
		assertThat(types(outbox.since(mark))).as("nothing while leaving, paused and returning").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		long resumedAt = clock.millis();
		room.resume();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("the room moves on at once").containsExactly("prepare");
		// No holdMs, and in a ranked room no correct key.
		assertPrepare(sent.get(0), 1, false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		Map<String, Object> song = room.currentSong(MINA.id());
		if (answerMode == AnswerMode.CHOICE) {
			assertThat(song.get("picked")).isNull();
		}
		if (gameMode == GameMode.RANKED) {
			assertThat(song).containsEntry("answered", false).containsEntry("correct", List.of());
		}

		// Past the original deadline: the old window's task does nothing, and the next song starts
		// by its prepare timeout (Mina is not ready) and stays open.
		scheduler.advance(Duration.ofMillis(deadlineAt + 6000 - clock.millis()));
		List<String> expected = new ArrayList<>(List.of("prepare"));
		expected.addAll(startTypes());
		assertThat(types(outbox.since(mark))).containsExactlyElementsOf(expected);
		assertThat(num(lastStart(), "index")).isEqualTo(1);
		assertThat(num(lastStart(), "serverTime")).isEqualTo(resumedAt + TIMEOUT_MS);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, RANKED, true"})
	void aResumeInTheLastSongsWindowGivesGameOver(AnswerMode answerMode, GameMode gameMode, boolean prefetch) {
		buildOn(answerMode, prefetch);
		startGame(gameMode);
		skipToTheLastSong();
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		scheduler.advance(Duration.ofSeconds(2));
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		scheduler.advance(Duration.ofSeconds(3));
		room.setConnected(MINA.id(), true);
		int mark = outbox.mark();
		room.resume();
		assertThat(types(outbox.since(mark))).containsExactly("gameOver");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		scheduler.advance(Duration.ofMillis(deadlineAt + 6000 - clock.millis()));
		assertThat(types(outbox.since(mark))).as("the old window's task does nothing").containsExactly("gameOver");
		assertThat(outbox.ofType("prepare")).hasSize(SONGS);
	}

	// While paused nothing runs, however long: the window does not end by itself.
	@Test
	void aPausedWindowDoesNotEndByItself() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		decide();
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		int mark = outbox.mark();
		scheduler.advance(LONG);
		assertThat(types(outbox.since(mark))).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		room.setConnected(JOON.id(), true);
		room.resume();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("prepare");
		assertPrepare(sent.get(0), 1, false);
	}

	// Prefetch: a pause after the delayed prepare is the pause of an early prepare of today (cycle 107).
	@Test
	void prefetchAResumeAfterTheDelayedPrepareSendsItAgainWithoutHoldMs() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toOffset(10_000);
		long deadlineAt = deadlineAt();
		decide();
		scheduler.advance(Duration.ofMillis(deadlineAt - 4000 - clock.millis()));
		int nextTheme = themeOf(lastPrepare());
		assertThat(num(lastPrepare(), "index")).isEqualTo(1);
		room.pause();
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(30));
		room.resume();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("prepare");
		assertPrepare(sent.get(0), 1, false);
		assertThat(themeOf(sent.get(0).message())).isEqualTo(nextTheme);
		mark = outbox.mark();
		long now = clock.millis();
		readyAll(1);
		assertThat(types(outbox.since(mark))).as("no hold after a resume").containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(now);
	}

	// ---- criterion 10: the switch off ----

	// The same three endings with the switch off, through the constructors of before this cycle and
	// through the new one with false: no playUntil key, and the next prepare 5 s after every reveal.
	@ParameterizedTest
	@CsvSource({"OFF_OLD, CHAT, FIRST", "OFF_OLD, CHOICE, FIRST", "OFF_OLD, CHAT, RANKED", "OFF_OLD, CHOICE, RANKED",
			"OFF_NEW, CHAT, FIRST", "OFF_NEW, CHOICE, FIRST", "OFF_NEW, CHAT, RANKED", "OFF_NEW, CHOICE, RANKED"})
	void switchOffEveryRevealHasNoPlayUntilAndLastsFiveSeconds(Switch sw, AnswerMode answerMode, GameMode gameMode) {
		build(answerMode, false, sw, MINA, JOON);
		startGame(gameMode);
		toOffset(10_000);
		long revealAt = clock.millis();
		assertRevealOff(revealIn(decide()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		int mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);

		play(1);
		long deadlineAt = deadlineAt();
		mark = outbox.mark();
		List<Sent> sent = sentExactlyAt(mark, deadlineAt);
		assertThat(types(sent)).containsExactly("reveal", "scores");
		assertRevealOff(revealIn(sent));
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, deadlineAt + REVEAL_MS, 2);

		play(2);
		toOffset(10_000);
		long skipAt = clock.millis();
		sent = skipByBoth();
		assertThat(types(sent)).containsExactly("skipState", "skipState", "reveal", "scores");
		assertRevealOff(revealIn(sent));
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, skipAt + REVEAL_MS, 3);
	}

	@ParameterizedTest
	@CsvSource({"OFF_OLD, CHAT, FIRST", "OFF_OLD, CHOICE, RANKED", "OFF_NEW, CHAT, FIRST", "OFF_NEW, CHOICE, FIRST",
			"OFF_NEW, CHAT, RANKED", "OFF_NEW, CHOICE, RANKED"})
	void switchOffWithPrefetchTheNextPrepareStillFollowsTheDecisionAtOnce(Switch sw, AnswerMode answerMode,
			GameMode gameMode) {
		build(answerMode, true, sw, MINA, JOON);
		startGame(gameMode);
		toOffset(10_000);
		long revealAt = clock.millis();
		int mark = outbox.mark();
		if (gameMode == GameMode.RANKED) {
			minaAnswersFirst();
			mark = outbox.mark();
			if (answerMode == AnswerMode.CHOICE) {
				room.pick(JOON.id(), 0, wrong);
			}
			else {
				room.chat(JOON.id(), title);
			}
		}
		else if (answerMode == AnswerMode.CHOICE) {
			room.pick(MINA.id(), 0, correct);
		}
		else {
			room.chat(MINA.id(), title);
		}
		List<Sent> sent = outbox.since(mark);
		List<String> expected = new ArrayList<>(decisionTypes());
		expected.add("prepare");
		assertThat(types(sent)).containsExactlyElementsOf(expected);
		assertRevealOff(revealIn(sent));
		assertPrepare(sent.get(sent.size() - 1), 1, true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		mark = outbox.mark();
		readyAll(1);
		List<Sent> started = sentExactlyAt(mark, revealAt + REVEAL_MS);
		assertThat(types(started)).containsExactlyElementsOf(startTypes());
	}

	// Switch off: the last song's reveal ends with gameOver after 5 s, as today.
	@ParameterizedTest
	@EnumSource(value = Switch.class, names = {"OFF_OLD", "OFF_NEW"})
	void switchOffTheLastSongEndsFiveSecondsAfterItsReveal(Switch sw) {
		build(AnswerMode.CHAT, false, sw, MINA, JOON);
		startGame(GameMode.FIRST);
		skipToTheLastSong();
		toOffset(10_000);
		long revealAt = clock.millis();
		assertRevealOff(revealIn(decide()));
		int mark = outbox.mark();
		assertThat(types(sentExactlyAt(mark, revealAt + REVEAL_MS))).containsExactly("gameOver");
	}

}
