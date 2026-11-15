package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 133 (D-132, B-038) criteria 1-7b at Room level, on the cycle 036 fakes: before the song starts
// (PREPARE, or PLAYING before startAt) a Room player's line that matches the song's accepted titles
// goes to its sender only and never scores; any other line goes to everyone. A watcher's chat stays
// sender only, and from startAt on nothing changes (docs/cycles/133-early-chat-broadcast.md).
// The fixture is 30 covered anime, each its own group with one OP of 60 s (ChoiceFixture); the
// current song is found by resolving the token in prepare.audioUrl, and its only title is
// ChoiceFixture.title(a).
class RoomEarlyChatTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	private static final String MISS = "is it the one with the robots";

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private void build(PlayerRef... members) {
		build(false, false, members);
	}

	private void build(boolean prefetchOnReveal, boolean playToLimit, PlayerRef... members) {
		built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, 30));
		players = List.of(members);
		room = new Room(built.catalog(), tokens, scheduler, clock, new Random(133), outbox, players, built.covers(),
				prefetchOnReveal, playToLimit);
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

	private long startAt() {
		return num(outbox.last("start").message(), "startAt");
	}

	private long deadlineAt() {
		return num(outbox.last("start").message(), "deadlineAt");
	}

	// The title of the song in the last prepare.
	private String title() {
		String url = (String) lastPrepare().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return ChoiceFixture.title(built.animeIndexOfTheme(themeId));
	}

	private List<Sent> chat(PlayerRef p, String text) {
		int mark = outbox.mark();
		room.chat(p.id(), text);
		return outbox.since(mark);
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// From PREPARE of that index: everyone readies, then the clock goes to one millisecond before startAt.
	private void toJustBeforeStartAt(int index) {
		readyAll(index);
		scheduler.advance(Room.LEAD.minusMillis(1));
		assertThat(clock.millis()).isEqualTo(startAt() - 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	private void toStartAt() {
		long left = startAt() - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		assertThat(clock.millis()).isEqualTo(startAt());
	}

	// Lets every player's chat bucket refill, so one player can send several lines in one test.
	private void refill() {
		scheduler.advance(Duration.ofSeconds(3));
	}

	private static void assertToAll(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one chat to everyone for " + text).hasSize(1);
		assertThat(sent.get(0).toAll()).as("to everyone: " + text).isTrue();
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	private static void assertOnlyToSender(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one chat to the sender only for " + text).hasSize(1);
		assertThat(sent.get(0).toAll()).as("not to everyone: " + text).isFalse();
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	private void assertNothingScored() {
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores().values()).containsOnly(0);
	}

	// ---- criterion 1: chat room, first-come, PREPARE ----

	@Test
	void inPrepareAMissReachesEveryoneAndAMatchOnlyItsSenderWithNoScore() {
		build(MINA, JOON);
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		String title = title();

		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertOnlyToSender(chat(MINA, title), MINA, title);
		assertToAll(chat(JOON, "hello"), JOON, "hello");
		assertOnlyToSender(chat(JOON, title.toUpperCase()), JOON, title.toUpperCase());

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(0);
	}

	// ---- criterion 2: PLAYING before startAt ----

	@Test
	void beforeStartAtAMissReachesEveryoneAndAMatchOnlyItsSenderWithNoScore() {
		build(MINA, JOON);
		room.start(5);
		String title = title();
		readyAll(0);
		assertThat(clock.millis()).isLessThan(startAt());

		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertOnlyToSender(chat(JOON, title), JOON, title);

		scheduler.advance(Room.LEAD.minusMillis(1));
		assertThat(clock.millis()).isEqualTo(startAt() - 1);
		assertToAll(chat(JOON, "almost"), JOON, "almost");
		assertOnlyToSender(chat(MINA, title), MINA, title);

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- criterion 3: from startAt, as today ----

	@Test
	void fromStartAtAMissReachesEveryoneAndAMatchScoresAndEndsTheSong() {
		build(MINA, JOON);
		room.start(5);
		String title = title();
		readyAll(0);
		toStartAt();

		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		List<Sent> sent = chat(JOON, title);
		assertThat(types(sent)).startsWith("correct", "chat", "reveal");
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("name", "Joon");
		assertThat(sent.get(1).toAll()).as("the winning text goes to all (D-094)").isTrue();
		assertThat(sent.get(1).message()).isEqualTo(Map.of("type", "chat", "name", "Joon", "text", title));
		assertThat(room.scores()).containsEntry(JOON.id(), 1).containsEntry(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- criterion 4: choice room (since D-135 every line reaches everyone; RoomChoiceChatTest) ----

	@Test
	void inAChoiceRoomBeforeTheStartAMissAndAMatchBothReachEveryone() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		String title = title();

		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertToAll(chat(MINA, title), MINA, title);

		toJustBeforeStartAt(0);
		assertToAll(chat(JOON, "which cover"), JOON, "which cover");
		assertToAll(chat(JOON, title), JOON, title);

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- criterion 5: ranked chat room ----

	@Test
	void inARankedRoomBeforeTheStartAMissReachesEveryoneAndAMatchOnlyItsSenderAndDoesNotRank() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		String title = title();

		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertOnlyToSender(chat(MINA, title), MINA, title);
		toJustBeforeStartAt(0);
		assertToAll(chat(JOON, "no idea"), JOON, "no idea");
		assertOnlyToSender(chat(JOON, title), JOON, title);

		assertNothingScored();
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", false);
		assertThat(room.currentSong(JOON.id())).containsEntry("answered", false);

		// Not ranked early: Mina's title from startAt is still the first correct answer.
		scheduler.advance(Duration.ofMillis(1));
		List<Sent> sent = chat(MINA, title);
		assertThat(sent.get(0).type()).isEqualTo("correct");
		assertThat(sent.get(0).message()).containsEntry("name", "Mina").containsEntry("rank", 1);
	}

	// ---- criterion 6: punctuation only ----

	@Test
	void aPunctuationOnlyLineBeforeTheStartReachesEveryone() {
		build(MINA, JOON);
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertToAll(chat(MINA, "?"), MINA, "?");
		assertToAll(chat(JOON, "..."), JOON, "...");

		toJustBeforeStartAt(0);
		assertToAll(chat(MINA, "!!"), MINA, "!!");
		assertToAll(chat(JOON, "?!"), JOON, "?!");

		assertNothingScored();
	}

	// ---- criterion 7: a watcher, unchanged ----

	@Test
	void aWatchersChatBeforeTheStartReachesOnlyTheWatcherMatchOrMiss() {
		build(MINA, JOON);
		room.start(5);
		String title = title();
		room.addPlayer(HANA);
		assertThat(room.isPlayer(HANA.id())).isFalse();

		assertOnlyToSender(chat(HANA, MISS), HANA, MISS);
		assertOnlyToSender(chat(HANA, title), HANA, title);

		toJustBeforeStartAt(0);
		assertOnlyToSender(chat(HANA, "still watching"), HANA, "still watching");
		assertOnlyToSender(chat(HANA, title), HANA, title);

		assertNothingScored();
	}

	// ---- criterion 7a: ranked, the re-prepare of a resume ----

	@Test
	void inARankedResumesReprepareAnAnsweredPlayersTitleReachesOnlyThemAndAMissReachesEveryone() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		String title = title();
		readyAll(0);
		toStartAt();
		List<Sent> answered = chat(MINA, title);
		assertThat(answered.get(0).message()).containsEntry("type", "correct").containsEntry("rank", 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		room.pause();
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(0);
		assertThat(title()).as("the same song is prepared again").isEqualTo(title);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", true);
		Map<String, Integer> scores = room.scores();
		refill();

		assertOnlyToSender(chat(MINA, title), MINA, title);
		assertToAll(chat(MINA, MISS), MINA, MISS);
		assertToAll(chat(JOON, "what was it"), JOON, "what was it");

		toJustBeforeStartAt(0);
		assertOnlyToSender(chat(MINA, title), MINA, title);
		assertToAll(chat(MINA, "soon"), MINA, "soon");

		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).isEqualTo(scores);
	}

	// ---- criterion 7b: prefetch, after the hold and before the next start ----

	@Test
	void withPrefetchAfterTheHoldTheNextSongsTitleReachesOnlyItsSenderAndAMissReachesEveryone() {
		build(true, false, MINA, JOON);
		room.start(5);
		String song0 = title();
		readyAll(0);
		toStartAt();
		List<Sent> won = chat(MINA, song0);
		assertThat(types(won)).contains("correct", "reveal", "prepare");
		assertThat(num(lastPrepare(), "index")).isEqualTo(1);
		String song1 = title();
		assertThat(song1).isNotEqualTo(song0);

		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(outbox.since(mark))).as("nobody ready: no start after the hold").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);

		assertOnlyToSender(chat(JOON, song1), JOON, song1);
		assertToAll(chat(JOON, MISS), JOON, MISS);
		assertToAll(chat(MINA, "next one"), MINA, "next one");
		assertOnlyToSender(chat(MINA, song1), MINA, song1);

		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0);
	}

	@Test
	void withPrefetchAndPlayToLimitTheHoldKeepsD120AndAfterItTheNextTitleIsHiddenAndAMissIsShown() {
		build(true, true, MINA, JOON);
		room.start(5);
		String song0 = title();
		readyAll(0);
		toStartAt();
		long deadlineAt = deadlineAt();
		List<Sent> won = chat(MINA, song0);
		assertThat(types(won)).contains("correct", "reveal").doesNotContain("prepare");

		// The early prepare goes out one reveal pause before the window's end: the hold.
		scheduler.advance(Duration.ofMillis(deadlineAt - REVEAL_MS - clock.millis()));
		assertThat(num(lastPrepare(), "index")).isEqualTo(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		String song1 = title();
		assertThat(song1).isNotEqualTo(song0);

		// During the hold: D-120, unchanged.
		assertOnlyToSender(chat(JOON, song1), JOON, song1);
		assertToAll(chat(JOON, MISS), JOON, MISS);

		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(outbox.since(mark))).as("nobody ready: no start after the hold").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);

		// After the hold, before the start: the new rule.
		assertOnlyToSender(chat(MINA, song1), MINA, song1);
		assertToAll(chat(MINA, "after the hold"), MINA, "after the hold");

		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0);
	}

}
