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

// Cycle 127 (B-035, D-129) at Room level, on the cycle 036 fakes: skip votes in the reveal window
// behind the switch playToLimit (docs/cycles/127-skip-in-window.md, criteria 1 to 11).
//
// No new API: the room is built with the 10-argument constructor of cycle 126, and votes go through
// skip, setConnected and clearSkipVote as in PLAYING.
//
// Built like RoomPlayToLimitTest, with its helpers copied here. Every song lasts at least 90 s, so
// deadlineAt = startAt + 90 s, and "decided at 10 s" is 10 s after startAt. The rooms have three
// players (Mina, Joon, Hana) unless a test says otherwise, so a skip needs 2 votes.
// How a song is decided: first come, Mina's correct chat or pick. Ranked, Mina and Joon answer
// correctly at 4 s and Hana's correct chat (chat) or wrong pick (choice) decides it later.
// A chat line costs a chat token and a pick costs none, so at the decision only the deciding chat's
// sender has spent a token: Joon's bucket is always full then.
class RoomWindowSkipTest {

	// How the room under test is built: with the switch on, with the 10-argument constructor and the
	// switch off, or with the constructors that have no switch.
	enum Switch {
		ON, OFF_NEW, OFF_OLD
	}

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final long[] LONG_SONGS = {90000, 120000, 95000, 100000, 110000, 130000, 91000};

	private static final long CHOICE_DURATION_MS = 120000;

	private static final long SEED = 127;

	private static final int SONGS = 5;

	private static final long MAX_PLAY_MS = Room.MAX_PLAY.toMillis();

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	private static final long LEAD_MS = Room.LEAD.toMillis();

	private static final long TIMEOUT_MS = Room.PREPARE_TIMEOUT.toMillis();

	// When the early answers of a ranked song are given, after startAt.
	private static final long EARLY_MS = 4000;

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

	private boolean prefetch;

	// The current song, as capture() read it from its prepare.
	private int index;

	private String title;

	private int correct;

	private int wrong;

	// ---- building ----

	private void build(AnswerMode answerMode, boolean prefetchOnReveal, Switch sw, PlayerRef... members) {
		answer = answerMode;
		prefetch = prefetchOnReveal;
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

	// The switch on, three players.
	private void buildOn(AnswerMode answerMode, boolean prefetchOnReveal) {
		build(answerMode, prefetchOnReveal, Switch.ON, MINA, JOON, HANA);
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
		assertPlaying(k);
	}

	private void assertPlaying(int k) {
		assertThat(room.phase()).as("song %d playing", k).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isEqualTo(k);
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

	// Ranked only: every player but the last answers correctly, now, which reveals nothing.
	private void earlyAnswers() {
		for (PlayerRef p : players.subList(0, players.size() - 1)) {
			int mark = outbox.mark();
			if (answer == AnswerMode.CHOICE) {
				room.pick(p.id(), index, correct);
				assertThat(types(outbox.since(mark))).containsExactly("pickState", "correct");
			}
			else {
				room.chat(p.id(), title);
				assertThat(types(outbox.since(mark))).containsExactly("correct", "chat");
				assertThat(outbox.since(mark).get(1).to()).as("the correct text goes to its sender only").isEqualTo(p.id());
			}
		}
		assertThat(room.phase()).as("the last player has not answered: no reveal").isEqualTo(Room.Phase.PLAYING);
	}

	// Moves to that offset of the current song, ready to decide it there. In a ranked room the early
	// answers are given on the way, at 4 s, so that their senders' buckets are full again by then.
	private void toDecisionPoint(long ms) {
		if (mode == GameMode.RANKED) {
			toOffset(EARLY_MS);
			earlyAnswers();
		}
		toOffset(ms);
	}

	// Decides the current song by answers, now: first come, Mina's correct chat or pick; ranked, the
	// last player's correct chat or wrong pick. Returns the frames of that action.
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

	// The frames of a decision as cycle 126 left them.
	private List<String> decisionTypes() {
		if (answer == AnswerMode.CHAT) {
			return List.of("correct", "chat", "reveal", "scores");
		}
		return mode == GameMode.RANKED ? List.of("pickState", "pick-result", "wrong", "reveal", "scores")
				: List.of("pickState", "correct", "reveal", "scores");
	}

	private static List<String> plus(List<String> types, String... more) {
		List<String> all = new ArrayList<>(types);
		all.addAll(List.of(more));
		return all;
	}

	// Decides the current song before its deadline and checks criterion 1: the frames of cycle 126,
	// then skipState{index, votes: 0, needed} to everyone, with the room in REVEAL until deadlineAt.
	private List<Sent> decideOpen(int needed) {
		long deadlineAt = deadlineAt();
		assertThat(clock.millis()).as("decided before the deadline").isLessThan(deadlineAt);
		List<Sent> sent = decide();
		assertThat(types(sent)).as("the frames of cycle 126, then skipState")
				.containsExactlyElementsOf(plus(decisionTypes(), "skipState"));
		Map<String, Object> reveal = revealIn(sent);
		assertThat(num(reveal, "index")).isEqualTo(index);
		assertThat(reveal.get("winner")).isEqualTo("Mina");
		assertThat(num(reveal, "playUntil")).as("playUntil").isEqualTo(deadlineAt);
		assertThat(sent.get(sent.size() - 2).type()).as("skipState comes right after scores").isEqualTo("scores");
		assertSkipState(sent.get(sent.size() - 1), index, 0, needed);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isEqualTo(index);
		return sent;
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

	// Exactly skipState{index, votes, needed}, to everyone.
	private static void assertSkipState(Sent sent, int songIndex, int votes, int needed) {
		assertThat(sent.type()).isEqualTo("skipState");
		assertThat(sent.toAll()).as("skipState goes to everyone").isTrue();
		Map<String, Object> message = sent.message();
		assertThat(message.keySet()).as("skipState keys").containsExactlyInAnyOrder("type", "index", "votes", "needed");
		assertThat(num(message, "index")).as("skipState index").isEqualTo(songIndex);
		assertThat(num(message, "votes")).as("skipState votes").isEqualTo(votes);
		assertThat(num(message, "needed")).as("skipState needed").isEqualTo(needed);
	}

	// A vote for the current song that changes the count and nothing else: exactly one skipState.
	private void voteCounts(PlayerRef voter, int votes, int needed) {
		int mark = outbox.mark();
		room.skip(voter.id(), index);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("%s's vote sends exactly skipState", voter.name()).containsExactly("skipState");
		assertSkipState(sent.get(0), index, votes, needed);
	}

	// A vote that must be ignored: no frame at all.
	private void voteIgnored(PlayerRef voter, int songIndex) {
		int mark = outbox.mark();
		Room.Phase before = room.phase();
		room.skip(voter.id(), songIndex);
		assertThat(types(outbox.since(mark))).as("%s's vote for song %d is ignored", voter.name(), songIndex).isEmpty();
		assertThat(room.phase()).isEqualTo(before);
	}

	// Shows that the sender's bucket is full at this clock instant, so that nothing took a token from
	// it since it was full: five chat lines go through and a sixth is refused. The clock is not moved.
	private void assertFullBucket(PlayerRef sender) {
		long at = clock.millis();
		int mark = outbox.mark();
		for (int i = 1; i <= 6; i++) {
			room.chat(sender.id(), "line " + i);
		}
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("%s still has five chat tokens: no token was spent", sender.name())
				.containsExactly("chat", "chat", "chat", "chat", "chat", "error");
		for (int i = 0; i < 5; i++) {
			assertThat(sent.get(i).message()).containsEntry("name", sender.name()).containsEntry("text", "line " + (i + 1));
		}
		assertThat(sent.get(5).to()).as("the refusal goes to its sender").isEqualTo(sender.id());
		assertThat(sent.get(5).message()).isEqualTo(Map.of("type", "error", "code", "rate-limited"));
		assertThat(clock.millis()).as("the clock did not move").isEqualTo(at);
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

	// Nothing is sent up to and at that moment.
	private void assertSilentThrough(int mark, long at) {
		assertThat(types(sentExactlyAt(mark, at))).as("no frame at the moment itself").isEmpty();
	}

	// Nothing until that moment, then exactly the prepare of that song, with no holdMs.
	private void assertNextPrepareExactlyAt(int mark, long at, int songIndex) {
		List<Sent> sent = sentExactlyAt(mark, at);
		assertThat(types(sent)).as("exactly the next prepare").containsExactly("prepare");
		assertPrepare(sent.get(0), songIndex, false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(songIndex);
	}

	// The end of an untouched window of the current song, from that mark: with prefetch the held
	// prepare of the next song exactly 5 s before deadlineAt, else its prepare exactly at deadlineAt.
	private void assertWindowEndsOnTime(int mark, long deadlineAt, int next) {
		if (prefetch) {
			List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
			assertThat(types(delayed)).as("exactly the delayed prepare").containsExactly("prepare");
			assertPrepare(delayed.get(0), next, true);
			assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		}
		else {
			assertNextPrepareExactlyAt(mark, deadlineAt, next);
		}
	}

	// From PLAYING: Mina and Joon vote and the skip passes. Returns the frames of the two votes.
	private List<Sent> skipInPlaying() {
		int mark = outbox.mark();
		room.skip(MINA.id(), index);
		room.skip(JOON.id(), index);
		return outbox.since(mark);
	}

	// After a reveal that lasts 5 s (a deadline or a skip in PLAYING): on to song k, PLAYING.
	private void afterFiveSecondReveal(int k) {
		if (prefetch) {
			// The prepare of song k came with the reveal and is held for the 5 s.
			capture();
			assertThat(index).isEqualTo(k);
			readyAll(k);
			scheduler.advance(Room.REVEAL_PAUSE);
			assertPlaying(k);
		}
		else {
			scheduler.advance(Room.REVEAL_PAUSE);
			play(k);
		}
	}

	// Ends songs 0 to 3 by a skip passed in PLAYING and leaves the last song (4) PLAYING.
	private void skipToTheLastSong() {
		for (int k = 0; k < SONGS - 1; k++) {
			assertThat(types(skipInPlaying())).contains("reveal");
			afterFiveSecondReveal(k + 1);
		}
		assertThat(outbox.ofType("prepare")).hasSize(SONGS);
	}

	private long revealCount(int songIndex) {
		return outbox.ofType("reveal").stream().filter(s -> num(s.message(), "index") == songIndex).count();
	}

	private long prepareCount(int songIndex) {
		return outbox.ofType("prepare").stream().filter(s -> num(s.message(), "index") == songIndex).count();
	}

	// ---- criterion 1: skipState at the reveal ----

	// Criteria 1 and 10: every way a song is decided by answers, with and without prefetch.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void theDecisionIsFollowedBySkipStateWithNoVotes(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		List<Sent> sent = decideOpen(2);
		assertThat(sent.stream().filter(s -> !"chat".equals(s.type()) && !"pick-result".equals(s.type())))
				.as("everything but an own chat or pick-result goes to everyone").allMatch(Sent::toAll);

		// Nobody votes: the window ends as in cycle 126, with no other skipState.
		int mark = outbox.mark();
		assertWindowEndsOnTime(mark, deadlineAt, 1);
		assertThat(outbox.ofType("skipState")).as("one skipState in all").hasSize(1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// needed is the majority of the connected Room players, as in PLAYING.
	@ParameterizedTest
	@CsvSource({"1, 1", "2, 2", "3, 2", "4, 3"})
	void neededAtTheRevealIsTheMajorityOfTheRoomPlayers(int count, int needed) {
		PlayerRef[] members = List.of(MINA, JOON, HANA, SORA).subList(0, count).toArray(new PlayerRef[0]);
		build(AnswerMode.CHAT, false, Switch.ON, members);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		decideOpen(needed);
	}

	@Test
	void neededAtTheRevealLeavesOutDisconnectedPlayers() {
		build(AnswerMode.CHAT, false, Switch.ON, MINA, JOON, HANA, SORA);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		room.setConnected(HANA.id(), false);
		room.setConnected(SORA.id(), false);
		// Two of four are connected: 2, not the 3 of four.
		decideOpen(2);
	}

	@Test
	void neededAtTheRevealLeavesOutWatchers() {
		build(AnswerMode.CHAT, false, Switch.ON, MINA, JOON);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		room.addPlayer(HANA);
		room.addPlayer(SORA);
		assertThat(room.isPlayer(HANA.id())).isFalse();
		// Two Room players and two watchers: 2, not the 3 of four.
		decideOpen(2);
	}

	// A reveal at the deadline and one by a skip passed in PLAYING are not skippable: no skipState.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void aDeadlineRevealAndASkipRevealAddNoSkipState(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		long deadlineAt = deadlineAt();
		int mark = outbox.mark();
		List<Sent> sent = sentExactlyAt(mark, deadlineAt);
		assertThat(types(sent)).as("a deadline reveal").containsExactlyElementsOf(
				early ? List.of("reveal", "scores", "prepare") : List.of("reveal", "scores"));
		assertThat(num(revealIn(sent), "playUntil")).isEqualTo(deadlineAt);
		assertThat(outbox.ofType("skipState")).isEmpty();

		afterFiveSecondReveal(1);
		toOffset(10_000);
		long skipAt = clock.millis();
		sent = skipInPlaying();
		assertThat(types(sent)).as("a skip reveal").containsExactlyElementsOf(
				early ? List.of("skipState", "skipState", "reveal", "scores", "prepare")
						: List.of("skipState", "skipState", "reveal", "scores"));
		assertSkipState(sent.get(0), 1, 1, 2);
		assertSkipState(sent.get(1), 1, 2, 2);
		assertThat(num(revealIn(sent), "playUntil")).isEqualTo(skipAt);
		assertThat(skipAt).as("deadlineAt is still ahead").isLessThan(deadlineAt());
	}

	// The clock is set and the scheduler not advanced: the chat itself finds the deadline passed.
	@ParameterizedTest
	@CsvSource({"0", "700"})
	void aRevealByAChatAtOrAfterTheDeadlineAddsNoSkipState(long late) {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		clock.advance(Duration.ofMillis(deadlineAt() + late - clock.millis()));
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		assertThat(types(outbox.since(mark))).containsExactly("reveal", "scores", "chat");
	}

	// Prefetch on, decided in the last 5 s: the next prepare comes with the reveal, the room is in
	// PREPARE and nothing can be skipped. advance(0) runs a zero-delay task, as in cycle 126.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, 2000", "CHAT, FIRST, 5000", "CHOICE, FIRST, 5000", "CHAT, RANKED, 5000",
			"CHOICE, RANKED, 2000"})
	void prefetchASongDecidedInItsLastFiveSecondsAddsNoSkipState(AnswerMode answerMode, GameMode gameMode,
			long beforeDeadline) {
		buildOn(answerMode, true);
		startGame(gameMode);
		toDecisionPoint(MAX_PLAY_MS - beforeDeadline);
		int mark = outbox.mark();
		decide();
		scheduler.advance(Duration.ZERO);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactlyElementsOf(plus(decisionTypes(), "prepare"));
		assertPrepare(sent.get(sent.size() - 1), 1, true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		voteIgnored(JOON, 0);
		voteIgnored(JOON, 1);
		assertFullBucket(JOON);
	}

	// The boundary of the case above: 5001 ms before the deadline the delayed prepare is 1 ms away, so
	// the reveal is skippable for that millisecond.
	@Test
	void prefetchASongDecidedJustOverFiveSecondsBeforeTheDeadlineIsSkippableUntilTheDelayedPrepare() {
		buildOn(AnswerMode.CHAT, true);
		startGame(GameMode.FIRST);
		toDecisionPoint(MAX_PLAY_MS - REVEAL_MS - 1);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		voteCounts(JOON, 1, 2);
		int mark = outbox.mark();
		List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
		assertThat(types(delayed)).containsExactly("prepare");
		assertPrepare(delayed.get(0), 1, true);
		// Hana's vote would have passed 1 ms earlier.
		voteIgnored(HANA, 0);
	}

	// ---- criterion 2: votes from before the decision ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aVoteCastBeforeTheDecisionDoesNotCountAfterIt(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toDecisionPoint(5000);
		voteCounts(JOON, 1, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		toOffset(10_000);
		// votes: 0 at the reveal, checked by decideOpen.
		decideOpen(2);

		// Joon's new vote is a first vote: 1, not a toggle back to 0.
		voteCounts(JOON, 1, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void anotherPlayersVoteAfterTheDecisionDoesNotAddToAnEarlierVote() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toDecisionPoint(5000);
		voteCounts(JOON, 1, 2);
		toOffset(10_000);
		decideOpen(2);
		// With Joon's earlier vote kept this would be the second vote and pass.
		voteCounts(HANA, 1, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- criterion 3: a vote passes in the window ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void twoOfThreeVotingInTheWindowMoveTheRoomOnAtOnce(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		Map<String, Integer> scores = room.scores();
		scheduler.advance(Duration.ofSeconds(3));
		voteCounts(JOON, 1, 2);
		assertThat(room.phase()).as("one of two needed votes").isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Duration.ofSeconds(1));

		long passAt = clock.millis();
		int mark = outbox.mark();
		room.skip(HANA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("no reveal, no scores, in the same turn").containsExactly("skipState", "prepare");
		assertSkipState(sent.get(0), 0, 2, 2);
		// No holdMs, and in a ranked room no correct key: the rows of song 0 were cleared.
		assertPrepare(sent.get(1), 1, false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(room.scores()).as("a passed vote scores nothing").isEqualTo(scores);
		Map<String, Object> song = room.currentSong(MINA.id());
		assertThat(song).isNotNull();
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(song.get("audioUrl")).isEqualTo(sent.get(1).message().get("audioUrl"));
		if (answerMode == AnswerMode.CHOICE) {
			assertThat(song.get("picked")).as("no pick of song 0 is left").isNull();
			@SuppressWarnings("unchecked")
			Map<String, Object> pickState = (Map<String, Object>) song.get("pickState");
			assertThat(num(pickState, "picked")).isEqualTo(0);
			assertThat(num(pickState, "total")).isEqualTo(3);
		}
		if (gameMode == GameMode.RANKED) {
			assertThat(song).containsEntry("answered", false).containsEntry("correct", List.of());
		}

		// The next song is not held: it starts as soon as everyone is ready.
		capture();
		assertThat(index).isEqualTo(1);
		readyAll(1);
		assertThat(types(outbox.since(mark))).containsExactlyElementsOf(plus(List.of("skipState", "prepare"),
				startTypes().toArray(new String[0])));
		assertPlaying(1);
		assertThat(num(lastStart(), "serverTime")).isEqualTo(passAt);
		assertThat(startAt()).isEqualTo(passAt + LEAD_MS);
		long nextDeadlineAt = deadlineAt();
		assertThat(nextDeadlineAt).as("song 1 outlasts the old window").isGreaterThan(deadlineAt + REVEAL_MS + 1000);

		// Up to and past the original deadlineAt nothing of the old window is left: no held prepare
		// 5 s before it, no prepare or reveal at it, nothing 5 s after it.
		mark = outbox.mark();
		assertSilentThrough(mark, deadlineAt - REVEAL_MS);
		assertSilentThrough(mark, deadlineAt);
		assertSilentThrough(mark, deadlineAt + REVEAL_MS + 1000);
		assertThat(prepareCount(1)).as("one prepare of the next song").isEqualTo(1);
		assertThat(revealCount(0)).as("one reveal of the skipped song").isEqualTo(1);
		assertThat(outbox.ofType("gameOver")).isEmpty();
		assertThat(outbox.ofType("prepare")).hasSize(2);
		assertPlaying(1);

		// The next song runs normally: decided now, it opens its own window until its own deadline.
		if (gameMode == GameMode.RANKED) {
			earlyAnswers();
		}
		sent = decideOpen(2);
		assertThat(num(revealIn(sent), "index")).isEqualTo(1);
		mark = outbox.mark();
		assertWindowEndsOnTime(mark, nextDeadlineAt, 2);
		assertThat(revealCount(1)).isEqualTo(1);
	}

	// Nobody is ready for the next song: it starts by its own prepare timeout, counted from the pass.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, RANKED, false", "CHAT, RANKED, true", "CHOICE, FIRST, true"})
	void afterAPassInTheWindowTheNextSongStartsByItsPrepareTimeout(AnswerMode answerMode, GameMode gameMode,
			boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(4));
		voteCounts(MINA, 1, 2);
		long passAt = clock.millis();
		int mark = outbox.mark();
		room.skip(JOON.id(), 0);
		assertThat(types(outbox.since(mark))).containsExactly("skipState", "prepare");

		mark = outbox.mark();
		List<Sent> started = sentExactlyAt(mark, passAt + TIMEOUT_MS);
		assertThat(types(started)).containsExactlyElementsOf(startTypes());
		assertThat(num(started.get(0).message(), "index")).isEqualTo(1);
		assertThat(num(started.get(0).message(), "serverTime")).isEqualTo(passAt + TIMEOUT_MS);
		mark = outbox.mark();
		assertSilentThrough(mark, deadlineAt + REVEAL_MS + 1000);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// Design 125 chose no minimum reveal time: two votes right at the decision pass at once.
	@ParameterizedTest
	@CsvSource({"false", "true"})
	void aVoteCanPassAtTheInstantOfTheDecision(boolean early) {
		buildOn(AnswerMode.CHAT, early);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		decideOpen(2);
		voteCounts(JOON, 1, 2);
		int mark = outbox.mark();
		room.skip(HANA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState", "prepare");
		assertSkipState(sent.get(0), 0, 2, 2);
		assertPrepare(sent.get(1), 1, false);
	}

	// ---- criterion 4: the last song ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void onTheLastSongAPassedVoteGivesSkipStateAndGameOver(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		skipToTheLastSong();
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		room.skip(HANA.id(), SONGS - 1);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState", "gameOver");
		assertSkipState(sent.get(0), SONGS - 1, 2, 2);
		assertThat(sent.get(1).toAll()).isTrue();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> ranking = (List<Map<String, Object>>) sent.get(1).message().get("ranking");
		assertThat(ranking).hasSize(3);
		assertThat(ranking.get(0)).containsEntry("name", "Mina").containsEntry("rank", 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		scheduler.advance(LONG);
		assertThat(types(outbox.since(mark))).as("the old window's task does nothing").containsExactly("skipState",
				"gameOver");
		assertThat(outbox.ofType("prepare")).as("no prepare after the last song").hasSize(SONGS);
		assertThat(revealCount(SONGS - 1)).isEqualTo(1);
	}

	// ---- criterion 5: a vote toggles ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, RANKED, true"})
	void aVoteInTheWindowToggles(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(5));
		voteCounts(JOON, 1, 2);
		voteCounts(JOON, 0, 2);
		// Joon's vote is gone: Hana's is the first again.
		voteCounts(HANA, 1, 2);
		voteCounts(HANA, 0, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		// With no vote standing the window ends when it would have.
		int mark = outbox.mark();
		assertWindowEndsOnTime(mark, deadlineAt, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// ---- criterion 6: votes that are ignored, with no frame and no chat token spent ----

	// No prefetch, decided 2 s before the deadline: the reveal stays for its 5 s minimum, but a skip
	// is possible only until playUntil, the deadline.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aVoteAtOrAfterPlayUntilIsIgnoredWhileTheRevealStaysOpen(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toDecisionPoint(MAX_PLAY_MS - 2000);
		long deadlineAt = deadlineAt();
		long revealAt = clock.millis();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(1));
		// 1 s before playUntil a vote still counts.
		voteCounts(MINA, 1, 2);

		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(1));
		assertThat(clock.millis()).as("exactly at playUntil").isEqualTo(deadlineAt);
		assertThat(types(outbox.since(mark))).as("nothing happens at playUntil").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		// Joon's vote would be the second and pass.
		voteIgnored(JOON, 0);
		assertFullBucket(JOON);

		mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		voteIgnored(HANA, 0);
		assertFullBucket(HANA);
		// Mina's standing vote is not toggled off either.
		voteIgnored(MINA, 0);

		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	// Prefetch on: the delayed prepare moves the room to PREPARE for the next song, which ends the
	// time a skip is possible, 5 s before the deadline.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void prefetchAVoteAfterTheDelayedPrepareIsIgnored(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, true);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(10));
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		List<Sent> delayed = sentExactlyAt(mark, deadlineAt - REVEAL_MS);
		assertThat(types(delayed)).as("exactly the delayed prepare, with a vote standing").containsExactly("prepare");
		assertPrepare(delayed.get(0), 1, true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		// For the old index Hana's vote would be the second; the new index is not PLAYING yet.
		voteIgnored(HANA, 0);
		voteIgnored(HANA, 1);
		voteIgnored(MINA, 0);
		assertFullBucket(HANA);

		capture();
		mark = outbox.mark();
		readyAll(1);
		assertThat(types(outbox.since(mark))).as("ready during the hold starts nothing").isEmpty();
		assertThat(types(sentExactlyAt(mark, deadlineAt))).containsExactlyElementsOf(startTypes());
		assertPlaying(1);
		// Joon's vote of the window does not count for song 1.
		voteCounts(HANA, 1, 2);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void aWatchersVoteInTheWindowIsIgnored(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		voteCounts(JOON, 1, 2);
		int mark = outbox.mark();
		room.addPlayer(SORA);
		assertThat(types(outbox.since(mark))).as("a join in the window sends nothing").isEmpty();
		assertThat(room.isPlayer(SORA.id())).isFalse();

		// Sora's vote would be the second.
		voteIgnored(SORA, 0);
		assertFullBucket(SORA);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		// And a watcher does not raise needed: Joon's toggle still shows 2.
		voteCounts(JOON, 0, 2);
	}

	// Hana's return re-sends the count (criterion 9) because Joon's vote stands.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void aDisconnectedPlayersVoteInTheWindowIsIgnored(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		voteCounts(JOON, 1, 2);
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 1, 2);

		// Hana's vote would be the second.
		voteIgnored(HANA, 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		room.setConnected(HANA.id(), true);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 1, 2);
		assertFullBucket(HANA);
	}

	// At the very instant of the decision, by Joon, whose bucket is full.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, RANKED, true"})
	void aVoteForAnotherIndexInTheWindowIsIgnored(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		voteIgnored(JOON, 1);
		voteIgnored(JOON, -1);
		voteIgnored(JOON, SONGS);
		assertFullBucket(JOON);
		// The window is open: a vote for the current index counts.
		voteCounts(HANA, 1, 2);
	}

	// The burst is shown right after the resume, with the clock not advanced since the vote: a paused
	// room ignores chat too.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void aVoteInAPausedWindowIsIgnored(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		voteCounts(JOON, 1, 2);
		room.pause();
		assertThat(room.paused()).isTrue();
		long pausedAt = clock.millis();

		// Hana's vote would be the second.
		voteIgnored(HANA, 0);
		voteIgnored(JOON, 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		int mark = outbox.mark();
		room.resume();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("the resume moves on, with no skipState").containsExactly("prepare");
		assertPrepare(sent.get(0), 1, false);
		assertThat(clock.millis()).as("the clock did not move since the vote").isEqualTo(pausedAt);
		assertFullBucket(HANA);
	}

	// The design's one condition also holds for a replaced socket in a paused window: nothing is sent.
	@Test
	void clearSkipVoteInAPausedWindowSendsNothing() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		voteCounts(JOON, 1, 2);
		room.pause();
		int mark = outbox.mark();
		room.clearSkipVote(JOON.id());
		room.clearSkipVote(HANA.id());
		assertThat(types(outbox.since(mark))).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// Criteria 6 and 11: with the switch off a vote in REVEAL is ignored as today, and no skipState
	// follows any reveal.
	@ParameterizedTest
	@CsvSource({"OFF_OLD, CHAT, FIRST", "OFF_OLD, CHOICE, FIRST", "OFF_OLD, CHAT, RANKED", "OFF_OLD, CHOICE, RANKED",
			"OFF_NEW, CHAT, FIRST", "OFF_NEW, CHOICE, FIRST", "OFF_NEW, CHAT, RANKED", "OFF_NEW, CHOICE, RANKED"})
	void switchOffNoSkipStateFollowsARevealAndAVoteInRevealIsIgnored(Switch sw, AnswerMode answerMode,
			GameMode gameMode) {
		build(answerMode, false, sw, MINA, JOON, HANA);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long revealAt = clock.millis();
		List<Sent> sent = decide();
		assertThat(types(sent)).as("the frames of today").containsExactlyElementsOf(decisionTypes());
		assertThat(revealIn(sent)).doesNotContainKey("playUntil");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		voteIgnored(JOON, 0);
		assertFullBucket(JOON);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		voteIgnored(HANA, 0);
		voteIgnored(MINA, 0);
		assertFullBucket(HANA);
		mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		room.clearSkipVote(JOON.id());
		assertThat(types(outbox.since(mark))).isEmpty();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(outbox.ofType("skipState")).isEmpty();

		// A skip in PLAYING is as today, and its reveal has no skipState after it.
		play(1);
		toOffset(10_000);
		sent = skipInPlaying();
		assertThat(types(sent)).containsExactly("skipState", "skipState", "reveal", "scores");
		assertSkipState(sent.get(0), 1, 1, 2);
		assertSkipState(sent.get(1), 1, 2, 2);
		assertThat(revealIn(sent)).doesNotContainKey("playUntil");
	}

	@ParameterizedTest
	@CsvSource({"OFF_OLD, CHAT, FIRST", "OFF_OLD, CHOICE, RANKED", "OFF_NEW, CHAT, RANKED", "OFF_NEW, CHOICE, FIRST"})
	void switchOffWithPrefetchNoSkipStateFollowsARevealAndAVoteIsIgnored(Switch sw, AnswerMode answerMode,
			GameMode gameMode) {
		build(answerMode, true, sw, MINA, JOON, HANA);
		startGame(gameMode);
		toDecisionPoint(10_000);
		List<Sent> sent = decide();
		assertThat(types(sent)).containsExactlyElementsOf(plus(decisionTypes(), "prepare"));
		assertPrepare(sent.get(sent.size() - 1), 1, true);
		voteIgnored(JOON, 0);
		voteIgnored(JOON, 1);
		assertFullBucket(JOON);
		assertThat(outbox.ofType("skipState")).isEmpty();
	}

	// The case the room must store playUntil for: after a skip passed in PLAYING, deadlineAt is still
	// 80 s ahead, yet the 5 s reveal is not skippable.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void afterASkipPassedInPlayingAVoteDuringItsFiveSecondRevealIsIgnored(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toOffset(10_000);
		long skipAt = clock.millis();
		List<Sent> sent = skipInPlaying();
		assertThat(types(sent)).containsExactly("skipState", "skipState", "reveal", "scores");
		assertSkipState(sent.get(0), 0, 1, 2);
		assertSkipState(sent.get(1), 0, 2, 2);
		assertThat(num(revealIn(sent), "playUntil")).isEqualTo(skipAt);
		assertThat(deadlineAt() - skipAt).as("deadlineAt is far ahead").isEqualTo(MAX_PLAY_MS - 10_000);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		// The third player, at the instant of the reveal and 2 s into it.
		voteIgnored(HANA, 0);
		assertFullBucket(HANA);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(types(outbox.since(mark))).isEmpty();
		voteIgnored(HANA, 0);
		voteIgnored(JOON, 0);
		voteIgnored(MINA, 0);
		assertFullBucket(JOON);
		mark = outbox.mark();
		room.clearSkipVote(MINA.id());
		room.setConnected(HANA.id(), false);
		room.setConnected(HANA.id(), true);
		assertThat(types(outbox.since(mark))).as("nor does a replaced socket or a connection change send anything")
				.isEmpty();

		// The reveal is not cut short, nor stretched to deadlineAt.
		assertNextPrepareExactlyAt(mark, skipAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
		assertThat(outbox.ofType("skipState")).as("only the two votes in PLAYING").hasSize(2);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void aVoteOneSecondAfterADeadlineRevealIsIgnored(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		long deadlineAt = deadlineAt();
		int mark = outbox.mark();
		assertThat(types(sentExactlyAt(mark, deadlineAt))).containsExactly("reveal", "scores");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		voteIgnored(HANA, 0);
		voteIgnored(JOON, 0);
		voteIgnored(MINA, 0);
		assertFullBucket(HANA);
		mark = outbox.mark();
		assertNextPrepareExactlyAt(mark, deadlineAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
		assertThat(outbox.ofType("skipState")).isEmpty();
	}

	// ---- criterion 7: the flood limit ----

	// A vote in the window costs a chat token from the bucket chat uses, and a refused vote is told
	// to its sender as in PLAYING.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void aVoteInTheWindowCostsAChatTokenAndARefusedOneGetsRateLimitedForSkipVote(AnswerMode answerMode,
			GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		// Five votes at one instant empty Joon's bucket; the count ends at 1.
		voteCounts(JOON, 1, 2);
		voteCounts(JOON, 0, 2);
		voteCounts(JOON, 1, 2);
		voteCounts(JOON, 0, 2);
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		room.skip(JOON.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("the sixth vote is refused").containsExactly("error");
		assertThat(sent.get(0).to()).isEqualTo(JOON.id());
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "error", "code", "rate-limited", "for", "skipVote"));
		// The same bucket: Joon's chat is refused too.
		mark = outbox.mark();
		room.chat(JOON.id(), "hello");
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("error");
		assertThat(sent.get(0).message()).isEqualTo(Map.of("type", "error", "code", "rate-limited"));

		// The refused vote did not toggle Joon's standing vote: Hana's is the second and passes.
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		room.skip(HANA.id(), 0);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState", "prepare");
		assertSkipState(sent.get(0), 0, 2, 2);
	}

	// The bucket emptied by chat: the vote is refused until half a second has refilled one token.
	@Test
	void aVoteAfterAChatBurstIsRefusedUntilATokenIsBack() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(2));
		assertFullBucket(HANA);

		int mark = outbox.mark();
		room.skip(HANA.id(), 0);
		scheduler.advance(Duration.ofMillis(499));
		room.skip(HANA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("error", "error");
		for (Sent refused : sent) {
			assertThat(refused.to()).isEqualTo(HANA.id());
			assertThat(refused.message()).isEqualTo(Map.of("type", "error", "code", "rate-limited", "for", "skipVote"));
		}
		scheduler.advance(Duration.ofMillis(1));
		voteCounts(HANA, 1, 2);
	}

	// ---- criterion 8: disconnects ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, false", "CHAT, RANKED, false", "CHOICE, RANKED, false",
			"CHAT, FIRST, true", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, true"})
	void aDisconnectInTheWindowSendsTheCountAndCanPassTheVote(AnswerMode answerMode, GameMode gameMode,
			boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(3));
		voteCounts(MINA, 1, 2);

		// A non-voter leaves: two are connected, the majority is still 2. No pickState in a choice room.
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 1, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		// The other non-voter leaves: Mina's one vote is the majority of one.
		mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState", "prepare");
		assertSkipState(sent.get(0), 0, 1, 1);
		assertPrepare(sent.get(1), 1, false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);

		// The next song starts when the one connected player is ready, and the old window is gone.
		mark = outbox.mark();
		room.ready(MINA.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactlyElementsOf(startTypes());
		mark = outbox.mark();
		assertSilentThrough(mark, deadlineAt + REVEAL_MS + 1000);
		assertThat(revealCount(0)).isEqualTo(1);
		assertThat(prepareCount(1)).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void withNoVotesConnectionChangesInTheWindowSendNothing(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(3));
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		room.setConnected(HANA.id(), false);
		room.setConnected(MINA.id(), false);
		room.setConnected(MINA.id(), true);
		room.setConnected(HANA.id(), true);
		assertThat(types(outbox.since(mark))).as("no skipState with no votes, and no pickState").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertWindowEndsOnTime(mark, deadlineAt, 1);
	}

	// A disconnect that itself decides a ranked song is evaluated once, by the PLAYING rules: the count
	// of the standing vote, then the reveal with its fresh skipState, and nothing after that.
	@ParameterizedTest
	@EnumSource(AnswerMode.class)
	void rankedADisconnectThatDecidesTheSongIsNotEvaluatedAgainInTheWindow(AnswerMode answerMode) {
		buildOn(answerMode, false);
		startGame(GameMode.RANKED);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		voteCounts(MINA, 1, 2);
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactlyElementsOf(answerMode == AnswerMode.CHOICE
				? List.of("pickState", "skipState", "reveal", "scores", "skipState")
				: List.of("skipState", "reveal", "scores", "skipState"));
		assertSkipState(sent.get(sent.size() - 4), 0, 1, 2);
		assertSkipState(sent.get(sent.size() - 1), 0, 0, 2);
		assertThat(num(revealIn(sent), "playUntil")).isEqualTo(deadlineAt);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		// Mina's vote from PLAYING is gone.
		voteCounts(MINA, 1, 2);
	}

	// ---- criterion 9: returns and replaced sockets ----

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void theSoleVoterDisconnectingLowersTheCountAndItsReturnSendsNothing(AnswerMode answerMode, GameMode gameMode,
			boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(3));
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 0, 2);
		mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		assertThat(types(outbox.since(mark))).as("a return with no votes sends nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		// Joon's vote was removed: voting again is a first vote, not a toggle to 0.
		voteCounts(JOON, 1, 2);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void aReturnWhileAnotherPlayerHasAVoteResendsTheCount(AnswerMode answerMode, GameMode gameMode, boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(3));
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		assertThat(types(outbox.since(mark))).isEmpty();
		voteCounts(MINA, 1, 2);

		mark = outbox.mark();
		room.setConnected(HANA.id(), true);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 1, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@ParameterizedTest
	@CsvSource({"CHAT, FIRST, false", "CHOICE, FIRST, true", "CHAT, RANKED, true", "CHOICE, RANKED, false"})
	void clearSkipVoteForAVoterLowersTheCountAndTheRoomStaysInReveal(AnswerMode answerMode, GameMode gameMode,
			boolean early) {
		buildOn(answerMode, early);
		startGame(gameMode);
		toDecisionPoint(10_000);
		long deadlineAt = deadlineAt();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(3));
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		room.clearSkipVote(JOON.id());
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 0, 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isEqualTo(0);

		// A player with no vote: nothing, also for Joon now.
		mark = outbox.mark();
		room.clearSkipVote(JOON.id());
		room.clearSkipVote(HANA.id());
		room.clearSkipVote(MINA.id());
		assertThat(types(outbox.since(mark))).as("no vote to clear: no frame").isEmpty();
		assertWindowEndsOnTime(mark, deadlineAt, 1);
	}

	// No prefetch, decided 2 s before the deadline, one vote standing at playUntil: from then on a
	// replaced socket and connection changes send nothing and cannot move the room on.
	@ParameterizedTest
	@CsvSource({"CHAT, FIRST", "CHOICE, FIRST", "CHAT, RANKED", "CHOICE, RANKED"})
	void atOrAfterPlayUntilClearSkipVoteAndConnectionChangesSendNothing(AnswerMode answerMode, GameMode gameMode) {
		buildOn(answerMode, false);
		startGame(gameMode);
		toDecisionPoint(MAX_PLAY_MS - 2000);
		long deadlineAt = deadlineAt();
		long revealAt = clock.millis();
		decideOpen(2);
		scheduler.advance(Duration.ofSeconds(1));
		voteCounts(JOON, 1, 2);

		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(1));
		assertThat(clock.millis()).as("exactly at playUntil").isEqualTo(deadlineAt);
		// In a skippable window these would send skipState{1, 2} and then pass the vote with needed 1.
		room.setConnected(HANA.id(), false);
		room.setConnected(MINA.id(), false);
		assertThat(types(outbox.since(mark))).as("a disconnect at playUntil sends nothing").isEmpty();
		room.setConnected(MINA.id(), true);
		assertThat(types(outbox.since(mark))).as("a return at playUntil sends nothing").isEmpty();
		room.clearSkipVote(JOON.id());
		assertThat(types(outbox.since(mark))).as("clearSkipVote at playUntil sends nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		scheduler.advance(Duration.ofSeconds(1));
		room.setConnected(HANA.id(), true);
		room.clearSkipVote(JOON.id());
		room.setConnected(JOON.id(), false);
		assertThat(types(outbox.since(mark))).as("nor 1 s after playUntil").isEmpty();
		assertNextPrepareExactlyAt(mark, revealAt + REVEAL_MS, 1);
		assertThat(revealCount(0)).isEqualTo(1);
	}

	@Test
	void clearSkipVoteOneMillisecondBeforePlayUntilStillSendsTheCount() {
		buildOn(AnswerMode.CHAT, false);
		startGame(GameMode.FIRST);
		toDecisionPoint(MAX_PLAY_MS - 2000);
		decideOpen(2);
		voteCounts(JOON, 1, 2);
		scheduler.advance(Duration.ofMillis(1999));
		int mark = outbox.mark();
		room.clearSkipVote(JOON.id());
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertSkipState(sent.get(0), 0, 0, 2);
	}

}
