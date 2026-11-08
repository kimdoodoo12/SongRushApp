package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 093 (B-020) criteria 1 and 2 at Room level, on the cycle 036 fakes (fake clock, manual
// scheduler, seeded Random, recording Outbox, real AudioTokens) and GameFixture's catalog. Anime 0-3
// of the fixture have a cover; anime 4's index entry has no file, so it has none
// (docs/cycles/093-reveal-cover.md). The current song is found by resolving the token in
// prepare.audioUrl.
class RoomCoverTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	// Cover ids by fixture anime index; anime 4's file is never written.
	private static final List<String> IDS = List.of("Cq1Wm8Zt3Lp0Vb6Nk2Rx9A", "Dw2Xn7Yu4Ko1Uc5Mj3Sy8B",
			"Ev3Yo6Xi5Jp2Td4Lh4Tz7C", "Fx4Zp5Wo6Iq3Se3Kg5Ua6D", "Gy5Aq4Vn7Hr4Rf2Jf6Vb5E");

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private List<PlayerRef> players;

	// Writes cover-cache/index.json (anime 0-4) and the files of anime 0-3, and loads it.
	private CoverMap covers() {
		try {
			Path cache = Files.createDirectories(dir.resolve("cover-cache"));
			List<String> entries = new ArrayList<>();
			for (int i = 0; i < IDS.size(); i++) {
				int size = 20 + i;
				if (i < 4) {
					Files.write(cache.resolve(IDS.get(i) + ".jpg"), new byte[size]);
				}
				entries.add("\"%d\": {\"id\": \"%s\", \"file\": \"%s.jpg\", \"size\": %d}"
						.formatted(GameFixture.animeId(i), IDS.get(i), IDS.get(i), size));
			}
			Files.writeString(cache.resolve("index.json"),
					"{\"version\": 1, \"covers\": {" + String.join(", ", entries) + "}}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		CoverMap map = CoverMap.loadDisk(dir.resolve("cover-cache"));
		assertThat(map.size()).as("fixture cover map").isEqualTo(4);
		return map;
	}

	private void build(CoverMap covers, PlayerRef... members) {
		catalog = GameFixture.build(dir, FIVE, 0);
		players = List.of(members);
		room = covers == null ? new Room(catalog, tokens, scheduler, clock, new Random(93), outbox, players)
				: new Room(catalog, tokens, scheduler, clock, new Random(93), outbox, players, covers);
	}

	// ---- helpers ----

	private Theme currentTheme() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return catalog.theme(themeId).orElseThrow();
	}

	private Anime currentAnime() {
		return catalog.anime(currentTheme().animeId()).orElseThrow();
	}

	// The fixture index of the current song's anime.
	private int currentFixtureIndex() {
		return currentAnime().id() - GameFixture.ANIME_BASE;
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
	}

	private void advanceToDeadline() {
		long deadline = ((Number) outbox.last("start").message().get("deadlineAt")).longValue();
		scheduler.advance(Duration.ofMillis(deadline - clock.millis()));
	}

	private static String json(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	// No cover id, no cover path and no coverUrl key in the message's text.
	private static void assertCarriesNoCover(Sent sent) {
		String text = json(sent.message());
		assertThat(text).as(sent.type() + " message").doesNotContain("coverUrl").doesNotContain("/cover/");
		for (String id : IDS) {
			assertThat(text).as(sent.type() + " message").doesNotContain(id);
		}
	}

	private static void assertCarriesNoCover(Map<String, Object> currentSong) {
		String text = json(currentSong);
		assertThat(text).as("currentSong").doesNotContain("coverUrl").doesNotContain("/cover/");
		for (String id : IDS) {
			assertThat(text).as("currentSong").doesNotContain(id);
		}
	}

	// Plays every song of a started game: the first song is won by a correct answer, the second is
	// skipped by vote, the others run to their deadline. Returns the fixture index per song index,
	// and checks currentSong() in PREPARE and PLAYING on the way.
	private List<Integer> playAll(int songCount) {
		List<Integer> order = new ArrayList<>();
		for (int index = 0; index < songCount; index++) {
			assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
			order.add(currentFixtureIndex());
			assertCarriesNoCover(room.currentSong());
			room.chat(MINA.id(), "not the title");
			readyAll(index);
			assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
			assertCarriesNoCover(room.currentSong());
			scheduler.advance(Room.LEAD);
			room.chat(JOON.id(), "a wrong guess");
			if (index == 0) {
				room.chat(MINA.id(), currentAnime().titles().get("en").get(0));
			}
			else if (index == 1) {
				for (PlayerRef p : players) {
					room.skip(p.id(), index);
				}
			}
			else {
				advanceToDeadline();
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
			assertThat(room.currentSong()).isNull();
			room.chat(JOON.id(), "after the reveal");
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		return order;
	}

	private static String expectedCoverUrl(int fixtureIndex) {
		return fixtureIndex < 4 ? "/cover/" + IDS.get(fixtureIndex) : null;
	}

	// ---- criterion 1 ----

	@Test
	void revealCarriesTheCoverUrlOfTheSongsAnimeOrNull() {
		build(covers(), MINA, JOON);
		room.start(5);
		List<Integer> order = playAll(5);
		assertThat(order).as("all five fixture anime are drawn").containsExactlyInAnyOrder(0, 1, 2, 3, 4);

		List<Sent> reveals = outbox.ofType("reveal");
		assertThat(reveals).hasSize(5);
		for (int index = 0; index < 5; index++) {
			Map<String, Object> reveal = reveals.get(index).message();
			assertThat(((Number) reveal.get("index")).intValue()).isEqualTo(index);
			assertThat(reveal).as("reveal %d", index).containsKey("coverUrl");
			assertThat(reveal.get("coverUrl")).as("reveal %d (anime %d)", index, order.get(index))
					.isEqualTo(expectedCoverUrl(order.get(index)));
		}
	}

	@Test
	void revealCoverUrlIsNullWithTheOldConstructor() {
		build(null, MINA);
		room.start(5);
		readyAll(0);
		advanceToDeadline();
		Map<String, Object> reveal = outbox.last("reveal").message();
		assertThat(reveal).containsKey("coverUrl");
		assertThat(reveal.get("coverUrl")).isNull();
	}

	@Test
	void revealCoverUrlIsNullWithAnEmptyMap() {
		build(CoverMap.empty(), MINA);
		room.start(5);
		readyAll(0);
		advanceToDeadline();
		Map<String, Object> reveal = outbox.last("reveal").message();
		assertThat(reveal).containsKey("coverUrl");
		assertThat(reveal.get("coverUrl")).isNull();
	}

	// Criterion 6 at Room level: a wrong-shape map is empty, so every coverUrl is null.
	@Test
	void everyCoverUrlIsNullWithAMalformedMap() throws IOException {
		Path cache = Files.createDirectories(dir.resolve("cover-cache"));
		Files.write(cache.resolve(IDS.get(0) + ".jpg"), new byte[20]);
		Files.writeString(cache.resolve("index.json"), "{\"version\": 1, \"covers\": [", StandardCharsets.UTF_8);
		build(CoverMap.loadDisk(cache), MINA, JOON);
		room.start(5);
		playAll(5);
		List<Sent> reveals = outbox.ofType("reveal");
		assertThat(reveals).hasSize(5);
		for (Sent reveal : reveals) {
			assertThat(reveal.message()).containsKey("coverUrl");
			assertThat(reveal.message().get("coverUrl")).isNull();
		}
	}

	// ---- criterion 2 ----

	@Test
	void noMessageButTheRevealCarriesACover() {
		build(covers(), MINA, JOON);
		room.start(5, DrawFilter.NONE);
		playAll(5);
		List<String> types = new ArrayList<>();
		for (Sent sent : outbox.all()) {
			types.add(sent.type());
			if (!"reveal".equals(sent.type())) {
				assertCarriesNoCover(sent);
			}
		}
		assertThat(types).contains("prepare", "start", "chat", "correct", "skipState", "scores", "gameOver");
	}

	// Each reveal names only its own song's cover, and nothing before it names that cover.
	@Test
	void aCoverIdFirstAppearsInItsOwnReveal() {
		build(covers(), MINA, JOON);
		room.start(5);
		List<Integer> order = playAll(5);
		List<Sent> all = outbox.all();
		Map<String, Integer> firstSeen = new HashMap<>();
		for (int i = 0; i < all.size(); i++) {
			String text = json(all.get(i).message());
			for (String id : IDS) {
				if (text.contains(id)) {
					firstSeen.putIfAbsent(id, i);
				}
			}
		}
		List<Sent> reveals = outbox.ofType("reveal");
		for (int index = 0; index < 5; index++) {
			int fixture = order.get(index);
			Sent reveal = reveals.get(index);
			String text = json(reveal.message());
			for (int other = 0; other < IDS.size(); other++) {
				if (other != fixture || fixture == 4) {
					assertThat(text).as("reveal %d names cover of anime %d", index, other)
							.doesNotContain(IDS.get(other));
				}
			}
			if (fixture < 4) {
				assertThat(firstSeen.get(IDS.get(fixture))).as("first message naming cover %d", fixture)
						.isEqualTo(all.indexOf(reveal));
			}
		}
		assertThat(firstSeen).doesNotContainKey(IDS.get(4));
	}

	// A late joiner added in PREPARE and in PLAYING gets nothing that names a cover before the reveal.
	@Test
	void lateJoinerGetsNoCoverBeforeTheReveal() {
		build(covers(), MINA, JOON);
		room.start(5);
		room.addPlayer(HANA);
		room.chat(HANA.id(), "watching");
		readyAll(0);
		scheduler.advance(Room.LEAD);
		room.chat(HANA.id(), "still watching");
		advanceToDeadline();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.isPlayer(HANA.id())).isTrue();
		for (Sent sent : outbox.all()) {
			if (!"reveal".equals(sent.type())) {
				assertCarriesNoCover(sent);
			}
		}
	}

}
