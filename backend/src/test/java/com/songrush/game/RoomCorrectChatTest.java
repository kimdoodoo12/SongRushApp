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
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 080 (D-094): the first correct guess in PLAYING is no longer hidden. Every member gets, in
// order, correct{index, name}, then chat{name, text} with the winner's original text, then the
// reveal frames. A guess before startAt and a watcher's guess still reach only the sender, and a
// correct guess after the song ended is a normal chat to all (docs/cycles/080-show-correct-chat.md).
class RoomCorrectChatTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef DORA = new PlayerRef("p4", "Dora");

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

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
		room = new Room(catalog, tokens, scheduler, clock, new Random(80), outbox, List.of(members));
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

	private List<Sent> chat(PlayerRef p, String text) {
		int mark = outbox.mark();
		room.chat(p.id(), text);
		return outbox.since(mark);
	}

	private Anime currentAnime() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	// The current song's English title with changed case, spaces and punctuation: it matches but is
	// not the stored title, so the echo must carry the text as typed.
	private String messyGuess() {
		String title = currentAnime().titles().get("en").get(0);
		String guess = "  " + title.toUpperCase().replace(" ", " - ") + "!! ";
		assertThat(guess).isNotEqualTo(title);
		assertThat(catalog.accepted(currentAnime().group())).contains(Normalize.normalize(guess));
		return guess;
	}

	private String exactTitle() {
		return currentAnime().titles().get("en").get(0);
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

	private static void assertOnlyToSender(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one message, to the sender only").hasSize(1);
		assertThat(sent.get(0).toAll()).isFalse();
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", sender.name(), "text", text));
	}

	// ---- order and content ----

	@Test
	void aCorrectGuessAtStartAtSendsCorrectThenTheChatThenTheRevealToEveryone() {
		build(MINA, JOON, HANA);
		int windowStart = outbox.mark();
		playSongZero(MINA, JOON, HANA);
		toStartAt();
		String guess = messyGuess();

		List<Sent> sent = chat(JOON, guess);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent).as("every frame goes to everyone").allMatch(Sent::toAll);

		assertThat(sent.get(0).message()).isEqualTo(Map.of("type", "correct", "index", 0, "name", "Joon"));
		assertThat(sent.get(1).message())
				.as("the winner's original text, not the normalized or stored title")
				.isEqualTo(Map.of("type", "chat", "name", "Joon", "text", guess));
		assertThat(sent.get(2).message()).containsEntry("winner", "Joon").containsEntry("index", 0);

		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.scores()).containsEntry(JOON.id(), 1).containsEntry(MINA.id(), 0)
				.containsEntry(HANA.id(), 0);

		// Nothing before correct carries the guess text.
		List<Sent> all = outbox.since(windowStart);
		int correctAt = types(all).indexOf("correct");
		assertThat(all.subList(0, correctAt))
				.noneMatch(s -> s.toAll() && "chat".equals(s.type()) && guess.equals(s.message().get("text")));
	}

	@Test
	void anExactTitleWellAfterStartAtIsEchoedAsTyped() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();
		scheduler.advance(Duration.ofSeconds(10));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		String title = exactTitle();

		List<Sent> sent = chat(MINA, title);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(1).message()).isEqualTo(Map.of("type", "chat", "name", "Mina", "text", title));
		assertThat(sent.get(2).message()).containsEntry("winner", "Mina");
	}

	@Test
	void aWrongGuessInPlayingIsStillOneChatToEveryoneWithNoCorrect() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();

		List<Sent> sent = chat(MINA, "not the answer at all");
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message())
				.isEqualTo(Map.of("type", "chat", "name", "Mina", "text", "not the answer at all"));
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- unchanged ----

	@Test
	void aCorrectGuessInPrepareReachesOnlyTheSender() {
		build(MINA, JOON);
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		String title = exactTitle();

		assertOnlyToSender(chat(MINA, title), MINA, title);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void aCorrectGuessJustBeforeStartAtReachesOnlyTheSender() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		long left = num(lastStart(), "startAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left - 1));
		String title = exactTitle();

		assertOnlyToSender(chat(JOON, title), JOON, title);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(JOON.id(), 0);
	}

	@Test
	void aWatchersCorrectGuessAfterStartAtReachesOnlyTheWatcher() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		room.addPlayer(DORA);
		toStartAt();
		String title = exactTitle();

		assertOnlyToSender(chat(DORA, title), DORA, title);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).as("the song goes on").isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aSecondCorrectGuessAfterTheSongEndedIsANormalChatToEveryone() {
		build(MINA, JOON);
		playSongZero(MINA, JOON);
		toStartAt();
		String title = exactTitle();
		assertThat(types(chat(MINA, title))).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		List<Sent> second = chat(JOON, title);
		assertThat(second).hasSize(1);
		assertThat(second.get(0).toAll()).isTrue();
		assertThat(second.get(0).message()).isEqualTo(Map.of("type", "chat", "name", "Joon", "text", title));
		assertThat(outbox.ofType("correct")).as("only the first correct answer").hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

}
