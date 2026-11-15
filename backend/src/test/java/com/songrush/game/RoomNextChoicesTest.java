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

// Cycle 134 (B-039, D-133) at Room level, on the cycle 036 fakes: in a choice room the reveal of a
// song that has a next song carries nextChoices, the next song's choices exactly as its prepare will
// carry them (docs/cycles/134-next-choices.md, criteria 1 to 5).
//
// No new API: the reveal frame gains the key nextChoices, and with it the window snapshot of cycle
// 128 (currentSong(playerId).reveal), which copies the broadcast reveal.
//
// Built like RoomPlayToLimitTest, with its helpers copied here. Every song lasts at least 90 s, so
// deadlineAt = startAt + 90 s, and "decided at 10 s" is 10 s after startAt. Two players (Mina, Joon),
// so a skip needs both votes. First come, Mina's correct chat or pick decides; ranked, Mina picks
// the correct cover and Joon's wrong pick decides. Each reveal is compared with the later prepare of
// the next song, read as soon as it is sent (its audio token expires).
class RoomNextChoicesTest {

	// How the room under test is built: with the 10-argument constructor and the switch playToLimit
	// on, with it and the switch off, or with the constructors that have no switch.
	enum Switch {
		ON, OFF_NEW, OFF_OLD
	}

	// How the song is ended: by answers at 10 s, by a passed skip at 10 s, or by its deadline task.
	enum End {
		DECIDED, SKIP, DEADLINE
	}

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final String UNKNOWN = "p-unknown";

	private static final long[] LONG_SONGS = {90000, 120000, 95000, 100000, 110000, 130000, 91000};

	private static final long CHOICE_DURATION_MS = 120000;

	private static final long SEED = 134;

	private static final int SONGS = 5;

	private static final long MAX_PLAY_MS = Room.MAX_PLAY.toMillis();

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

	private boolean switchOn;

	// The current song, as capture() read it from its prepare.
	private int index;

	private String title;

	private int correct;

	private int wrong;

	// ---- building ----

	private void build(AnswerMode answerMode, boolean prefetch, Switch sw) {
		answer = answerMode;
		switchOn = sw == Switch.ON;
		players = List.of(MINA, JOON);
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

	// The ChoiceFixture catalog loaded again with every song lasting CHOICE_DURATION_MS (the fixture's
	// own songs last 60 s, below the 90 s limit).
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

	// With the prepare of song k out and nobody held: reads the song and makes everyone ready.
	private void play(int k) {
		capture();
		assertThat(index).isEqualTo(k);
		readyAll(k);
		assertThat(room.phase()).as("song %d playing", k).isEqualTo(Room.Phase.PLAYING);
		assertThat(deadlineAt() - startAt()).as("a song of at least 90 s").isEqualTo(MAX_PLAY_MS);
	}

	// With the prepare of song k out, perhaps held (prefetch): reads the song, makes everyone ready and
	// advances in steps of 1 s until it plays.
	private void playNext(int k) {
		capture();
		assertThat(index).isEqualTo(k);
		readyAll(k);
		for (int second = 0; second <= 60 && room.phase() != Room.Phase.PLAYING; second++) {
			scheduler.advance(Duration.ofSeconds(1));
		}
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

	private void toOffset(long ms) {
		long at = startAt() + ms;
		assertThat(at).isGreaterThanOrEqualTo(clock.millis());
		scheduler.advance(Duration.ofMillis(at - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// Decides the current song by answers, now. First come: Mina's correct chat or pick. Ranked (choice
	// only here): Mina picks the correct cover, then Joon's wrong pick ends it.
	private void decide() {
		if (mode == GameMode.RANKED) {
			room.pick(MINA.id(), index, correct);
			assertThat(room.phase()).as("one of two answered: no reveal").isEqualTo(Room.Phase.PLAYING);
			room.pick(JOON.id(), index, wrong);
		}
		else if (answer == AnswerMode.CHOICE) {
			room.pick(MINA.id(), index, correct);
		}
		else {
			room.chat(MINA.id(), title);
		}
	}

	// Ends the current song that way and returns its reveal (the one reveal sent since).
	private Map<String, Object> end(End how) {
		int mark = outbox.mark();
		switch (how) {
			case DECIDED -> {
				toOffset(10_000);
				decide();
			}
			case SKIP -> {
				toOffset(10_000);
				room.skip(MINA.id(), index);
				room.skip(JOON.id(), index);
			}
			case DEADLINE -> scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		}
		List<Sent> reveals = outbox.since(mark).stream().filter(s -> "reveal".equals(s.type())).toList();
		assertThat(reveals).as("exactly one reveal").hasSize(1);
		assertThat(reveals.get(0).toAll()).as("the reveal goes to everyone").isTrue();
		Map<String, Object> reveal = reveals.get(0).message();
		assertThat(num(reveal, "index")).isEqualTo(index);
		return reveal;
	}

	// The prepare of that song, sent since the mark, possibly in the same turn as the reveal; advances
	// in steps of 1 s until it is out. Its theme id is read at once (the token expires).
	private Map<String, Object> awaitPrepare(int mark, int songIndex) {
		for (int second = 0; second <= 600; second++) {
			for (Sent s : outbox.since(mark)) {
				if ("prepare".equals(s.type()) && num(s.message(), "index") == songIndex) {
					assertThat(s.toAll()).as("prepare goes to everyone").isTrue();
					return s.message();
				}
			}
			scheduler.advance(Duration.ofSeconds(1));
		}
		throw new AssertionError("no prepare of song " + songIndex + " was sent");
	}

	// The keys of a reveal before this cycle, in this room's answer format and game mode.
	private List<String> todaysRevealKeys() {
		List<String> keys = new ArrayList<>(List.of("type", "index", "reveal", "titles", "song", "artists", "themeType",
				"themeSlug", "winner", "coverUrl"));
		if (answer == AnswerMode.CHOICE) {
			keys.add("correctChoice");
		}
		if (mode == GameMode.RANKED) {
			keys.add("ranking");
		}
		if (switchOn) {
			keys.add("playUntil");
		}
		return keys;
	}

	// Criteria 1, 2 and 4: the reveal has today's keys and nextChoices, nothing else; nextChoices is
	// exactly the later prepare's choices, a list of {coverUrl: "/cover/..."}; and no theme or anime id
	// of the next song appears anywhere in the reveal.
	@SuppressWarnings("unchecked")
	private void assertNextChoices(Map<String, Object> reveal, Map<String, Object> nextPrepare) {
		int nextTheme = themeOf(nextPrepare);
		List<String> expected = todaysRevealKeys();
		expected.add("nextChoices");
		assertThat(reveal.keySet()).as("reveal keys: today's and nextChoices")
				.containsExactlyInAnyOrderElementsOf(expected);
		assertThat(reveal.get("nextChoices")).as("nextChoices is the next prepare's choices")
				.isEqualTo(nextPrepare.get("choices"));

		List<Object> next = (List<Object>) reveal.get("nextChoices");
		assertThat(next).as("nextChoices").isNotEmpty();
		for (Object choice : next) {
			assertThat(choice).isInstanceOf(Map.class);
			Map<String, Object> c = (Map<String, Object>) choice;
			assertThat(c.keySet()).as("a choice has only its cover URL").containsExactly("coverUrl");
			assertThat((String) c.get("coverUrl")).as("an opaque cover URL").startsWith("/cover/");
		}
		int a = built.animeIndexOfTheme(nextTheme);
		assertThat(coverUrls(nextPrepare)).as("the next song's own cover is among them")
				.contains(ChoiceFixture.coverUrl(a));

		// Walked value by value: a timestamp such as playUntil may contain those digits by chance.
		List<Object> leaves = new ArrayList<>();
		collectLeaves(reveal, leaves);
		for (int id : List.of(nextTheme, ChoiceFixture.animeId(a))) {
			for (Object leaf : leaves) {
				if (leaf instanceof Number n) {
					assertThat(n.longValue()).as("no id of the next song in the reveal").isNotEqualTo(id);
				}
				else if (leaf instanceof String s) {
					assertThat(s).as("no id of the next song in the reveal").doesNotContain(Integer.toString(id));
				}
			}
		}
	}

	// Every key and leaf value of a frame, nested maps and lists included.
	private static void collectLeaves(Object value, List<Object> out) {
		if (value instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				out.add(e.getKey());
				collectLeaves(e.getValue(), out);
			}
		}
		else if (value instanceof List<?> list) {
			for (Object item : list) {
				collectLeaves(item, out);
			}
		}
		else if (value != null) {
			out.add(value);
		}
	}

	// ---- criterion 1: play-to-limit and prefetch on, decided at 10 s ----

	@ParameterizedTest
	@CsvSource({"FIRST", "RANKED"})
	void aChoiceRevealDecidedAtTenSecondsCarriesTheNextPreparesChoices(GameMode gameMode) {
		build(AnswerMode.CHOICE, true, Switch.ON);
		startGame(gameMode);
		int mark = outbox.mark();
		Map<String, Object> reveal = end(End.DECIDED);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(types(outbox.since(mark))).as("the prepare of song 1 is not yet out").doesNotContain("prepare");

		Map<String, Object> prepare = awaitPrepare(mark, 1);
		assertNextChoices(reveal, prepare);
	}

	// ---- criterion 2: with prefetch off, play-to-limit off, after a skip and after the deadline ----

	@ParameterizedTest
	@CsvSource({"ON, false, DECIDED, FIRST", "ON, false, DECIDED, RANKED", "OFF_NEW, true, DECIDED, FIRST",
			"OFF_NEW, false, DECIDED, FIRST", "OFF_NEW, false, DECIDED, RANKED", "OFF_OLD, true, DECIDED, FIRST",
			"OFF_OLD, false, DECIDED, FIRST", "ON, true, SKIP, FIRST", "ON, false, SKIP, FIRST",
			"OFF_NEW, false, SKIP, FIRST", "OFF_OLD, true, SKIP, RANKED", "ON, true, DEADLINE, FIRST",
			"ON, false, DEADLINE, RANKED", "OFF_NEW, true, DEADLINE, FIRST", "OFF_OLD, false, DEADLINE, FIRST"})
	void everyChoiceRevealWithANextSongCarriesItsChoices(Switch sw, boolean prefetch, End how, GameMode gameMode) {
		build(AnswerMode.CHOICE, prefetch, sw);
		startGame(gameMode);
		int mark = outbox.mark();
		Map<String, Object> reveal = end(how);
		Map<String, Object> prepare = awaitPrepare(mark, 1);
		assertNextChoices(reveal, prepare);

		// And so for the next song too: song 1's reveal carries song 2's choices.
		playNext(1);
		mark = outbox.mark();
		reveal = end(how);
		assertNextChoices(reveal, awaitPrepare(mark, 2));
	}

	// ---- criterion 3: no nextChoices in a chat room, nor on the last song's reveal ----

	@ParameterizedTest
	@CsvSource({"ON, true, DECIDED", "ON, false, DECIDED", "ON, false, SKIP", "ON, true, DEADLINE",
			"OFF_NEW, false, DECIDED", "OFF_OLD, true, DECIDED", "OFF_OLD, false, SKIP"})
	void aChatRoomsRevealHasNoNextChoices(Switch sw, boolean prefetch, End how) {
		build(AnswerMode.CHAT, prefetch, sw);
		startGame(GameMode.FIRST);
		int mark = outbox.mark();
		Map<String, Object> reveal = end(how);
		assertThat(reveal).doesNotContainKey("nextChoices");
		assertThat(reveal.keySet()).as("reveal keys as before this cycle")
				.containsExactlyInAnyOrderElementsOf(todaysRevealKeys());
		assertThat(outbox.since(mark)).as("no frame carries nextChoices")
				.noneMatch(s -> s.message().containsKey("nextChoices"));
	}

	@ParameterizedTest
	@CsvSource({"ON, true, DECIDED, FIRST", "ON, false, DECIDED, RANKED", "ON, false, SKIP, FIRST",
			"ON, true, DEADLINE, FIRST", "OFF_NEW, false, DECIDED, FIRST", "OFF_OLD, true, SKIP, FIRST",
			"OFF_OLD, false, DEADLINE, RANKED"})
	void theLastSongsRevealInAChoiceRoomHasNoNextChoices(Switch sw, boolean prefetch, End how, GameMode gameMode) {
		build(AnswerMode.CHOICE, prefetch, sw);
		startGame(gameMode);
		for (int k = 0; k < SONGS - 1; k++) {
			int mark = outbox.mark();
			end(End.SKIP);
			awaitPrepare(mark, k + 1);
			playNext(k + 1);
		}
		assertThat(index).isEqualTo(SONGS - 1);
		int mark = outbox.mark();
		Map<String, Object> reveal = end(how);
		assertThat(reveal).doesNotContainKey("nextChoices");
		assertThat(reveal.keySet()).as("the last reveal's keys as before this cycle")
				.containsExactlyInAnyOrderElementsOf(todaysRevealKeys());
		scheduler.advance(Duration.ofMinutes(10));
		assertThat(outbox.since(mark)).as("nothing after the last song carries nextChoices")
				.noneMatch(s -> s.message().containsKey("nextChoices"));
		assertThat(types(outbox.since(mark))).as("no prepare after the last song").doesNotContain("prepare");
	}

	// ---- criterion 5: the window snapshot carries nextChoices ----

	@ParameterizedTest
	@CsvSource({"true, FIRST", "false, FIRST", "true, RANKED", "false, RANKED"})
	@SuppressWarnings("unchecked")
	void aSnapshotInTheWindowCarriesTheRevealsNextChoices(boolean prefetch, GameMode gameMode) {
		build(AnswerMode.CHOICE, prefetch, Switch.ON);
		startGame(gameMode);
		int mark = outbox.mark();
		Map<String, Object> reveal = end(End.DECIDED);
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(room.phase()).as("still in the window").isEqualTo(Room.Phase.REVEAL);

		List<Map<String, Object>> snapshots = new ArrayList<>();
		for (String id : List.of(MINA.id(), JOON.id(), UNKNOWN)) {
			Map<String, Object> song = room.currentSong(id);
			assertThat(song).as("currentSong(%s) in the window", id).isNotNull();
			assertThat(song.get("reveal")).as("the snapshot has the reveal").isInstanceOf(Map.class);
			Map<String, Object> snapshotReveal = (Map<String, Object>) song.get("reveal");
			assertThat(snapshotReveal).as("reveal.nextChoices for %s", id).containsKey("nextChoices");
			assertThat(snapshotReveal.get("nextChoices")).as("the broadcast nextChoices")
					.isEqualTo(reveal.get("nextChoices"));
			snapshots.add(snapshotReveal);
		}

		Map<String, Object> prepare = awaitPrepare(mark, 1);
		assertNextChoices(reveal, prepare);
		for (Map<String, Object> snapshotReveal : snapshots) {
			assertThat(snapshotReveal.get("nextChoices")).as("the snapshot's nextChoices is the next prepare's choices")
					.isEqualTo(prepare.get("choices"));
		}
	}

}
