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
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 107 addendum (D-120, critic H2), at Room level on the cycle 036 fakes: the spoiler guard of the
// early mode "prefetch on reveal" (docs/cycles/107-prefetch-on-reveal.md, Addendum).
//
// While revealing song N (early mode, the prepare of song N + 1 already sent), a chat line that matches
// song N + 1's accepted titles goes to its sender only; every other line is broadcast as before;
// nothing scores. Default mode (prefetchOnReveal = false) is unchanged.
//
// Fixtures and helpers follow RoomPrefetchOnRevealTest: the current song is found by resolving the token
// in prepare.audioUrl, and GameFixture titles are unique per anime.
class RoomRevealSpoilerTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	private static final long SEED = 107;

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private List<PlayerRef> players;

	private void build(boolean prefetchOnReveal) {
		catalog = GameFixture.build(dir, SEVEN, 0);
		players = List.of(MINA, JOON, HANA);
		room = new Room(catalog, tokens, scheduler, clock, new Random(SEED), outbox, players, CoverMap.empty(),
				prefetchOnReveal);
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

	private int themeOf(Map<String, Object> prepare) {
		String url = (String) prepare.get("audioUrl");
		assertThat(url).startsWith("/audio/");
		return tokens.resolve(url.substring("/audio/".length())).orElseThrow();
	}

	private Theme currentTheme() {
		return catalog.theme(themeOf(outbox.last("prepare").message())).orElseThrow();
	}

	private Anime animeOf(Theme theme) {
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private String title(Theme theme, String lang) {
		return animeOf(theme).titles().get(lang).get(0);
	}

	// A messy spelling that normalization still accepts (case, spaces, punctuation).
	private String messy(Theme theme) {
		String text = "  " + title(theme, "en").toUpperCase().replace(" ", " - ") + "!! ";
		assertThat(catalog.accepted(animeOf(theme).group())).as("messy still matches")
				.contains(Normalize.normalize(text));
		return text;
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
	}

	// From LOBBY: starts a 5-song game, plays song 0 and lets Mina win it with the exact title.
	// Returns song 0's theme.
	private Theme playSongZeroToReveal() {
		room.start(5);
		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		Theme song0 = currentTheme();
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), title(song0, "en"));
		assertThat(types(outbox.since(mark))).as("answered").contains("correct", "reveal");
		return song0;
	}

	// The draw order of a seeded game on this catalog, found by a default-mode probe room with its own
	// clock, scheduler and outbox (as RoomPrefetchOnRevealTest.drawOrder).
	private List<Integer> drawOrder() {
		MutableClock probeClock = new MutableClock();
		ManualScheduler probeScheduler = new ManualScheduler(probeClock);
		RecordingOutbox probeOutbox = new RecordingOutbox();
		AudioTokens probeTokens = new AudioTokens(probeClock);
		Room probe = new Room(catalog, probeTokens, probeScheduler, probeClock, new Random(SEED), probeOutbox,
				List.of(MINA, JOON, HANA));
		probe.start(5);
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
		assertThat(probeOutbox.ofType("gameOver")).as("probe game finished").hasSize(1);
		assertThat(order).hasSize(5);
		return order;
	}

	private void assertOnlyToSender(Sent sent, PlayerRef sender, String text) {
		assertThat(sent.type()).isEqualTo("chat");
		assertThat(sent.toAll()).as("not broadcast: %s", text).isFalse();
		assertThat(sent.to()).as("only the sender sees it: %s", text).isEqualTo(sender.id());
		assertThat(sent.message()).containsEntry("name", sender.name()).containsEntry("text", text);
	}

	// ---- (1) a line matching song N + 1 goes to its sender only ----

	@Test
	void duringTheEarlyRevealALineMatchingTheNextSongGoesOnlyToItsSenderAndNeverScores() {
		build(true);
		Theme song0 = playSongZeroToReveal();
		assertThat(room.phase()).as("early mode: the next song is prepared").isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		Theme song1 = currentTheme();
		assertThat(song1.animeId()).isNotEqualTo(song0.animeId());
		Map<String, Integer> scores = room.scores();

		List<String> lines = List.of(title(song1, "en"), messy(song1), title(song1, "romaji"), title(song1, "ko"));
		int mark = outbox.mark();
		for (String line : lines) {
			room.chat(JOON.id(), line);
		}
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("one chat per line, no correct").containsExactly("chat", "chat", "chat", "chat");
		for (int i = 0; i < lines.size(); i++) {
			assertOnlyToSender(sent.get(i), JOON, lines.get(i));
		}
		assertThat(sent).as("no other player receives it").noneMatch(s -> MINA.id().equals(s.to()))
				.noneMatch(s -> HANA.id().equals(s.to()));
		assertThat(room.scores()).as("no score change").isEqualTo(scores);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
	}

	// ---- (2) song N's title and ordinary lines are broadcast as before ----

	@Test
	void duringTheEarlyRevealSongNsTitleAndOrdinaryLinesAreBroadcastAndNeverScore() {
		build(true);
		Theme song0 = playSongZeroToReveal();
		Map<String, Integer> scores = room.scores();
		List<String> lines = List.of(title(song0, "en"), messy(song0), "just talking");
		int mark = outbox.mark();
		for (String line : lines) {
			room.chat(JOON.id(), line);
		}
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat", "chat", "chat");
		for (int i = 0; i < lines.size(); i++) {
			assertThat(sent.get(i).toAll()).as("broadcast: %s", lines.get(i)).isTrue();
			assertThat(sent.get(i).message()).containsEntry("name", "Joon").containsEntry("text", lines.get(i));
		}
		assertThat(room.scores()).isEqualTo(scores);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- (3) after the reveal, song N + 1 is won as usual ----

	@Test
	void afterTheRevealEndsTheNextSongsTitleScoresNormallyForAnotherPlayer() {
		build(true);
		playSongZeroToReveal();
		Theme song1 = currentTheme();
		String song1Title = title(song1, "en");
		int mark = outbox.mark();
		room.chat(JOON.id(), song1Title);
		assertOnlyToSender(outbox.since(mark).get(0), JOON, song1Title);
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0)
				.containsEntry(HANA.id(), 0);

		readyAll(1);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isEqualTo(1);
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		mark = outbox.mark();
		room.chat(HANA.id(), song1Title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).startsWith("correct", "chat", "reveal");
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("name", "Hana");
		assertThat(num(sent.get(0).message(), "index")).isEqualTo(1);
		assertThat(sent.get(1).toAll()).as("the winning text is shown to all (D-094)").isTrue();
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0)
				.containsEntry(HANA.id(), 1);
	}

	// ---- (4) default mode is unchanged ----

	@Test
	void defaultModeDuringTheRevealALineEqualToTheNextSongsTitleIsBroadcastAsToday() {
		build(false);
		List<Integer> order = drawOrder();
		Theme song0 = playSongZeroToReveal();
		assertThat(song0.id()).as("probe matches the game").isEqualTo(order.get(0));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		Theme song1 = catalog.theme(order.get(1)).orElseThrow();
		String song1Title = title(song1, "en");
		Map<String, Integer> scores = room.scores();
		int mark = outbox.mark();
		room.chat(JOON.id(), song1Title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat");
		assertThat(sent.get(0).toAll()).as("broadcast as today").isTrue();
		assertThat(sent.get(0).message()).containsEntry("name", "Joon").containsEntry("text", song1Title);
		assertThat(room.scores()).isEqualTo(scores);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

}
