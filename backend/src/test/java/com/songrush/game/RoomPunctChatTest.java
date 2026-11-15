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
import com.songrush.catalog.Catalog;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 086 (D-100): a chat of only punctuation or symbols is a normal chat line that never scores.
// It follows every visibility rule of a miss: sender only for a watcher; to everyone before and
// after startAt, in prepare, in the lobby and after the reveal (D-132, cycle 133). Only an empty or
// whitespace-only chat is ignored (docs/cycles/086-punct-chat-paused-disc.md).
class RoomPunctChatTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef DORA = new PlayerRef("p4", "Dora");

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	// U+1F600, built from its code point so this file stays ASCII.
	private static final String EMOJI = new String(Character.toChars(0x1F600));

	// Each is sent by its own player so the chat rate limit (burst 5) never interferes.
	private static final List<String> PUNCT = List.of("?", "...", "!!", EMOJI);

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private void build(PlayerRef... members) {
		catalog = GameFixture.build(dir, FIVE, 0);
		room = new Room(catalog, tokens, scheduler, clock, new Random(86), outbox, List.of(members));
	}

	// ---- helpers ----

	private static long num(Map<String, Object> message, String key) {
		Object value = message.get(key);
		assertThat(value).as(key).isInstanceOf(Number.class);
		return ((Number) value).longValue();
	}

	private List<Sent> chat(PlayerRef p, String text) {
		int mark = outbox.mark();
		room.chat(p.id(), text);
		return outbox.since(mark);
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	// From LOBBY: every listed player readies song 0; the song is PLAYING but before startAt.
	private void playSongZero(PlayerRef... ps) {
		room.start(5);
		for (PlayerRef p : ps) {
			room.ready(p.id(), 0);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	private void toStartAt() {
		long left = num(lastStart(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		assertThat(clock.millis()).isEqualTo(num(lastStart(), "startAt"));
	}

	// Lets every player's chat bucket refill, so one player can send several lines in one test.
	private void refill() {
		scheduler.advance(Duration.ofSeconds(3));
	}

	private static void assertToAll(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one chat to everyone for " + text).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	private static void assertOnlyToSender(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one chat to the sender only for " + text).hasSize(1);
		assertThat(sent.get(0).toAll()).isFalse();
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	// ---- after startAt ----

	@Test
	void punctuationOnlyChatsAfterStartAtGoToEveryoneAndNeverScore() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();

		for (String text : PUNCT) {
			assertToAll(chat(MINA, text), MINA, text);
			assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
			refill();
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void theOriginalTextIsKeptWithItsSpaces() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();

		String text = "  ?! . / ,  ";
		assertToAll(chat(JOON, text), JOON, text);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- before the start: to everyone (D-132) ----

	@Test
	void punctuationOnlyChatsBeforeStartAtGoToEveryone() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));

		for (String text : List.of("?", "...")) {
			assertToAll(chat(MINA, text), MINA, text);
		}
		long left = num(lastStart(), "startAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left - 1));
		for (String text : List.of("!!", EMOJI)) {
			assertToAll(chat(JOON, text), JOON, text);
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void punctuationOnlyChatsInPrepareGoToEveryone() {
		build(MINA, JOON);
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		for (String text : PUNCT) {
			assertToAll(chat(MINA, text), MINA, text);
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void aWatchersPunctuationOnlyChatAfterStartAtReachesOnlyTheWatcher() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		room.addPlayer(DORA);
		toStartAt();

		for (String text : PUNCT) {
			assertOnlyToSender(chat(DORA, text), DORA, text);
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- to everyone outside the song ----

	@Test
	void punctuationOnlyChatsInTheLobbyGoToEveryone() {
		build(MINA, JOON);
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		for (String text : PUNCT) {
			assertToAll(chat(MINA, text), MINA, text);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	@Test
	void punctuationOnlyChatsAfterTheRevealGoToEveryone() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();
		scheduler.advance(Duration.ofMillis(num(lastStart(), "deadlineAt") - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		for (String text : PUNCT) {
			assertToAll(chat(JOON, text), JOON, text);
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- still ignored ----

	@Test
	void blankChatsSendNothingInAnyPhase() {
		build(MINA, JOON, HANA);
		List<String> blanks = List.of("", "  ", "\t");

		for (String text : blanks) {
			assertThat(chat(MINA, text)).as("lobby, [" + text + "]").isEmpty();
		}
		playSongZero(MINA, JOON, HANA);
		for (String text : blanks) {
			assertThat(chat(JOON, text)).as("before startAt, [" + text + "]").isEmpty();
		}
		toStartAt();
		for (String text : blanks) {
			assertThat(chat(HANA, text)).as("after startAt, [" + text + "]").isEmpty();
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// 086 review M1: a chat of only invisible characters is ignored like a blank one. Built from
	// code points so this file stays ASCII: U+3164 Hangul filler, U+200B ZWSP, U+00A0 NBSP, U+FEFF,
	// and U+3164 + space + U+200B. Five lines per player, one player per phase, so the burst of 5
	// is never exceeded.
	@Test
	void invisibleOnlyChatsSendNothingInAnyPhase() {
		build(MINA, JOON, HANA);
		List<String> invisibles = List.of(cp(0x3164), cp(0x200B), cp(0x00A0), cp(0xFEFF),
				cp(0x3164) + " " + cp(0x200B));

		for (String text : invisibles) {
			assertThat(chat(MINA, text)).as("lobby, " + codePoints(text)).isEmpty();
		}
		playSongZero(MINA, JOON, HANA);
		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));
		for (String text : invisibles) {
			assertThat(chat(JOON, text)).as("before startAt, " + codePoints(text)).isEmpty();
		}
		toStartAt();
		for (String text : invisibles) {
			assertThat(chat(HANA, text)).as("after startAt, " + codePoints(text)).isEmpty();
		}
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	private static String cp(int codePoint) {
		return new String(Character.toChars(codePoint));
	}

	private static String codePoints(String text) {
		StringBuilder out = new StringBuilder("[");
		text.codePoints().forEach(c -> out.append(String.format(" U+%04X", c)));
		return out.append(" ]").toString();
	}

}
