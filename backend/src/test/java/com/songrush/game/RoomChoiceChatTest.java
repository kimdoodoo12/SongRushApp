package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 136 (D-135) criteria 1-3 at Room level, on the cycle 036 fakes: in a choice room chat never
// scores, so every line that passes the flood limit reaches everyone at every moment. No D-132 check
// before the start, no D-120 check during a reveal with prefetch, and a watcher's line is shown too
// (docs/cycles/136-choice-chat-plain.md). Chat rooms keep those rules (RoomEarlyChatTest).
// The fixture is 30 covered anime, each its own group with one OP of 60 s (ChoiceFixture); the
// current song is found by resolving the token in prepare.audioUrl, and its only title is
// ChoiceFixture.title(a).
class RoomChoiceChatTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final String MISS = "is it the one with the robots";

	private static final int ANIME = 30;

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private void build(boolean prefetchOnReveal, PlayerRef... members) {
		built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, ANIME));
		players = List.of(members);
		room = new Room(built.catalog(), tokens, scheduler, clock, new Random(136), outbox, players, built.covers(),
				prefetchOnReveal, false);
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

	// The anime index of the song in the last prepare.
	private int currentAnime() {
		String url = (String) lastPrepare().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return built.animeIndexOfTheme(themeId);
	}

	// The titles of the last prepare's choices other than the answer.
	@SuppressWarnings("unchecked")
	private List<String> otherChoiceTitles(int answer) {
		List<String> titles = new ArrayList<>();
		for (Object choice : (List<Object>) lastPrepare().get("choices")) {
			String coverUrl = (String) ((Map<String, Object>) choice).get("coverUrl");
			for (int a = 0; a < ANIME; a++) {
				if (a != answer && ChoiceFixture.coverUrl(a).equals(coverUrl)) {
					titles.add(ChoiceFixture.title(a));
				}
			}
		}
		assertThat(titles).as("the other choices' titles").isNotEmpty();
		return titles;
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

	// Lets every chat bucket refill, so one player can send several lines in one test.
	private void refill() {
		scheduler.advance(Duration.ofSeconds(3));
	}

	private static void assertToAll(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one chat to everyone for " + text).hasSize(1);
		assertThat(sent.get(0).toAll()).as("to everyone: " + text).isTrue();
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	private void assertNothingScored() {
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores().values()).containsOnly(0);
	}

	// ---- criterion 1: before the start ----

	@Test
	void inPrepareTheAnswerAnotherChoiceAndAMissAllReachEveryone() {
		build(false, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		int answer = currentAnime();
		String title = ChoiceFixture.title(answer);
		String other = otherChoiceTitles(answer).get(0);

		assertToAll(chat(MINA, title), MINA, title);
		assertToAll(chat(MINA, other), MINA, other);
		assertToAll(chat(JOON, MISS), JOON, MISS);
		assertToAll(chat(JOON, title.toUpperCase()), JOON, title.toUpperCase());

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void beforeStartAtTheAnswerAnotherChoiceAndAMissAllReachEveryone() {
		build(false, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		int answer = currentAnime();
		String title = ChoiceFixture.title(answer);
		List<String> others = otherChoiceTitles(answer);
		readyAll(0);
		assertThat(clock.millis()).isLessThan(startAt());

		assertToAll(chat(MINA, title), MINA, title);
		assertToAll(chat(JOON, others.get(0)), JOON, others.get(0));
		assertToAll(chat(JOON, MISS), JOON, MISS);

		scheduler.advance(Room.LEAD.minusMillis(1));
		assertThat(clock.millis()).isEqualTo(startAt() - 1);
		// Within the flood limit (burst 5): three more lines each, with no time passing.
		for (String other : others.subList(0, Math.min(3, others.size()))) {
			assertToAll(chat(MINA, other), MINA, other);
		}
		assertToAll(chat(JOON, title), JOON, title);
		assertThat(clock.millis()).as("still before the start").isEqualTo(startAt() - 1);

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- criterion 2: during a reveal with prefetch ----

	@Test
	void withPrefetchDuringTheRevealTheNextSongsTitleReachesEveryone() {
		build(true, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		int song0 = currentAnime();
		readyAll(0);
		scheduler.advance(Room.LEAD);
		assertThat(clock.millis()).isEqualTo(startAt());

		// Both skip: the reveal starts and, with prefetch, song 1's prepare goes out with it.
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(types(outbox.since(mark))).contains("reveal", "prepare");
		assertThat(num(lastPrepare(), "index")).isEqualTo(1);
		int song1 = currentAnime();
		assertThat(song1).isNotEqualTo(song0);
		String next = ChoiceFixture.title(song1);

		assertToAll(chat(JOON, next), JOON, next);
		assertToAll(chat(MINA, next.toUpperCase()), MINA, next.toUpperCase());
		assertToAll(chat(MINA, MISS), MINA, MISS);
		String nextOther = otherChoiceTitles(song1).get(0);
		assertToAll(chat(JOON, nextOther), JOON, nextOther);

		assertNothingScored();
	}

	// ---- criterion 3: a watcher ----

	@Test
	void aWatchersLinesBeforeTheRevealReachEveryone() {
		build(false, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		String title = ChoiceFixture.title(currentAnime());
		room.addPlayer(HANA);
		assertThat(room.isPlayer(HANA.id())).isFalse();

		assertToAll(chat(HANA, MISS), HANA, MISS);
		assertToAll(chat(HANA, title), HANA, title);

		readyAll(0);
		assertThat(clock.millis()).isLessThan(startAt());
		assertToAll(chat(HANA, title), HANA, title);

		scheduler.advance(Duration.ofMillis(startAt() - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		refill();
		assertToAll(chat(HANA, title), HANA, title);
		assertToAll(chat(HANA, "still watching"), HANA, "still watching");

		assertNothingScored();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

}
