package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 094 (B-021) criteria 3 and 4 (easy): an easy game never draws two songs of the same series
// group, and easy with fewer distinct groups than N answers not-enough-songs with available = the
// number of groups and unit "series"; normal and hard keep the plain draw and count songs
// (docs/cycles/094-nested-tiers.md). One catalog, generated once into a static @TempDir: anime a
// (0-based, id ANIME_BASE + a, anime rank a + 1, year 2000 + a / 10) has two cached themes,
// THEME_BASE + 10 * a (OP) and THEME_BASE + 10 * a + 1 (ED), and series group GROUP_BASE + (a + 2) / 4,
// so groups span up to four anime. The easy pool (anime ranks 1-100) has 200 themes in 26 groups;
// group 25 holds anime ranks 99-102, so it straddles the easy boundary.
class RoomEasyNoRepeatTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int ANIME_BASE = 100000;

	private static final int THEME_BASE = 200000;

	private static final int GROUP_BASE = 300000;

	private static final long DURATION_MS = 60000;

	private static final int ANIME_COUNT = 120;

	private static final int EASY_GROUPS = 26;

	private static final DrawFilter EASY = new DrawFilter(null, null, null, "easy");

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	static Path dir;

	private static Catalog catalog;

	@BeforeAll
	static void buildCatalog() {
		catalog = build(dir);
		assertThat(catalog.candidates()).hasSize(2 * ANIME_COUNT);
	}

	// ---- fixture ----

	private static int groupOf(int a) {
		return GROUP_BASE + (a + 2) / 4;
	}

	private static int yearOf(int a) {
		return 2000 + a / 10;
	}

	private static int themeId(int a, int j) {
		return THEME_BASE + 10 * a + j;
	}

	private static int animeOf(int themeId) {
		return (themeId - THEME_BASE) / 10;
	}

	private static int groupOfTheme(int themeId) {
		return groupOf(animeOf(themeId));
	}

	private static String type(int j) {
		return j == 0 ? "OP" : "ED";
	}

	private static Catalog build(Path folder) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<Integer, List<String>> accepted = new TreeMap<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(folder.resolve("audio-cache"));
			for (int a = 0; a < ANIME_COUNT; a++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("en", List.of("Series Anime " + a));
				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", ANIME_BASE + a);
				an.put("group", groupOf(a));
				an.put("titles", titles);
				an.put("reveal", "Series Reveal " + a);
				an.put("year", yearOf(a));
				anime.add(an);
				accepted.computeIfAbsent(groupOf(a), g -> new ArrayList<>()).add(Normalize.normalize("Series Anime " + a));

				for (int j = 0; j < 2; j++) {
					int id = themeId(a, j);
					String basename = "Series" + a + "-" + type(j) + "1.ogg";
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", id);
					t.put("animeId", ANIME_BASE + a);
					t.put("type", type(j));
					t.put("slug", type(j) + "1");
					t.put("song", "Series Song " + a + "-" + j);
					t.put("artists", List.of("Series Singer " + a));
					t.put("audio", Map.of("basename", basename, "size", 1));
					themes.add(t);
					drawSet.add(id);
					index.put(Integer.toString(id), Map.of("basename", basename, "durationMs", DURATION_MS, "size", 1));
					Files.write(cache.resolve(basename), new byte[1]);
				}
			}
			List<Object> groups = new ArrayList<>();
			accepted.forEach((id, strings) -> groups.add(Map.of("id", id, "accepted", strings)));
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("version", 1);
			root.put("anime", anime);
			root.put("themes", themes);
			root.put("groups", groups);
			root.put("drawSet", drawSet);
			Path catalogJson = folder.resolve("catalog.json");
			Files.writeString(catalogJson, MAPPER.writeValueAsString(root));
			Path indexJson = cache.resolve("index.json");
			Files.writeString(indexJson, MAPPER.writeValueAsString(index));
			return CatalogLoader.load(catalogJson, indexJson);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// One room on fresh fakes, seeded.
	private static final class Game {

		final MutableClock clock = new MutableClock();

		final ManualScheduler scheduler = new ManualScheduler(clock);

		final RecordingOutbox outbox = new RecordingOutbox();

		final AudioTokens tokens = new AudioTokens(clock);

		final Room room;

		Game(long seed) {
			room = new Room(catalog, tokens, scheduler, clock, new Random(seed), outbox, List.of(MINA));
		}

		int currentThemeId() {
			String url = (String) outbox.last("prepare").message().get("audioUrl");
			assertThat(url).startsWith("/audio/");
			return tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		}

		// From the first prepare: every song is readied and skipped, then the reveal pause runs.
		// Returns the drawn theme ids in song order.
		List<Integer> playToGameOver(int count) {
			List<Integer> drawn = new ArrayList<>();
			for (int k = 0; k < count; k++) {
				assertThat(room.phase()).as("song " + k).isEqualTo(Room.Phase.PREPARE);
				assertThat(room.songIndex()).isEqualTo(k);
				drawn.add(currentThemeId());
				room.ready(MINA.id(), k);
				room.skip(MINA.id(), k);
				assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(outbox.ofType("gameOver")).hasSize(1);
			return drawn;
		}

	}

	// Plays one easy-or-other game per seed; checks each game has no repeated theme and, when
	// distinctGroups, no repeated group. Returns every theme id drawn.
	private static Set<Integer> drawMany(DrawFilter filter, int songs, int seeds, boolean distinctGroups) {
		Set<Integer> seen = new TreeSet<>();
		for (long seed = 0; seed < seeds; seed++) {
			Game game = new Game(seed);
			game.room.start(songs, filter);
			List<Integer> drawn = game.playToGameOver(songs);
			assertThat(drawn).as(filter + " seed " + seed).doesNotHaveDuplicates();
			if (distinctGroups) {
				List<Integer> groups = drawn.stream().map(RoomEasyNoRepeatTest::groupOfTheme).toList();
				assertThat(groups).as(filter + " seed " + seed + ": series groups of the game").doesNotHaveDuplicates();
			}
			seen.addAll(drawn);
		}
		return seen;
	}

	private static Set<Integer> pool(String type, Integer yearFrom, Integer yearTo, int lastAnimeRank) {
		Set<Integer> out = new TreeSet<>();
		for (int a = 0; a < ANIME_COUNT && a + 1 <= lastAnimeRank; a++) {
			if (yearFrom != null && yearOf(a) < yearFrom || yearTo != null && yearOf(a) > yearTo) {
				continue;
			}
			for (int j = 0; j < 2; j++) {
				if (type == null || type.equals(type(j))) {
					out.add(themeId(a, j));
				}
			}
		}
		return out;
	}

	private static Set<Integer> groups(Set<Integer> themeIds) {
		Set<Integer> out = new TreeSet<>();
		themeIds.forEach(id -> out.add(groupOfTheme(id)));
		return out;
	}

	private static Map<String, Object> notEnough(int available) {
		return Map.of("type", "error", "code", "not-enough-songs", "available", available);
	}

	private static Map<String, Object> notEnoughSeries(int available) {
		return Map.of("type", "error", "code", "not-enough-songs", "available", available, "unit", "series");
	}

	private static void assertNotEnough(int songs, DrawFilter filter, Map<String, Object> error) {
		Game game = new Game(7);
		game.room.start(songs, filter);
		assertThat(game.outbox.all()).as(filter + " " + songs + " songs").extracting(Sent::message)
				.containsExactly(error);
		assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(game.scheduler.pending()).isZero();
	}

	// ---- fixture sanity ----

	@Test
	void theEasyPoolHasMoreThemesThanGroupsAndGroupsSpanSeveralAnime() {
		Set<Integer> easy = pool(null, null, null, 100);
		assertThat(easy).hasSize(200);
		assertThat(groups(easy)).hasSize(EASY_GROUPS);
		// Group 25: anime ranks 99-102, of which 99 and 100 are in easy.
		assertThat(groupOf(98)).isEqualTo(groupOf(99)).isEqualTo(groupOf(100)).isEqualTo(groupOf(101));
		assertThat(groupOf(102)).isNotEqualTo(groupOf(101));
		assertThat(catalog.anime(ANIME_BASE + 98).orElseThrow().group()).isEqualTo(catalog.anime(ANIME_BASE + 101)
				.orElseThrow().group());
		assertThat(catalog.animeRank(themeId(99, 1))).isEqualTo(100);
		assertThat(catalog.animeRank(themeId(100, 0))).isEqualTo(101);
		assertThat(catalog.rank(themeId(99, 1))).as("anime rank 100's ED is past draw-set position 100")
				.isEqualTo(200);
	}

	// ---- criterion 3 ----

	@Test
	void noEasyGameRepeatsASeriesGroupAndEveryThemeOfThePoolCanBeDrawn() {
		Set<Integer> seen = drawMany(EASY, 20, 300, true);
		assertThat(seen).allSatisfy(id -> assertThat(animeOf(id) + 1).as("anime rank").isBetween(1, 100));
		assertThat(seen).as("every easy theme is drawn at some point").containsExactlyElementsOf(pool(null, null, null,
				100));
	}

	@Test
	void anEasyGameOfAsManySongsAsGroupsHasEachGroupOnce() {
		for (long seed = 0; seed < 40; seed++) {
			Game game = new Game(seed);
			game.room.start(EASY_GROUPS, EASY);
			List<Integer> drawn = game.playToGameOver(EASY_GROUPS);
			assertThat(new TreeSet<>(drawn.stream().map(RoomEasyNoRepeatTest::groupOfTheme).toList()))
					.as("seed " + seed).hasSize(EASY_GROUPS).containsExactlyElementsOf(groups(pool(null, null, null, 100)));
			assertThat(drawn).allSatisfy(id -> assertThat(animeOf(id)).as("anime index").isLessThan(100));
		}
	}

	@Test
	void easyWithTypeAndYearStillDrawsOneSongPerGroup() {
		// Easy OP of 2000-2004: anime ranks 1-50, 50 themes in 13 groups.
		DrawFilter filter = new DrawFilter(Set.of("OP"), 2000, 2004, "easy");
		Set<Integer> expected = pool("OP", 2000, 2004, 100);
		assertThat(expected).hasSize(50);
		assertThat(groups(expected)).hasSize(13);
		Set<Integer> seen = drawMany(filter, 13, 30, true);
		assertThat(seen).isSubsetOf(expected);
		assertNotEnough(14, filter, notEnoughSeries(13));
	}

	// ---- criterion 4 ----

	@Test
	void easyWithFewerGroupsThanSongsCountsGroups() {
		assertNotEnough(EASY_GROUPS + 1, EASY, notEnoughSeries(EASY_GROUPS));
		assertNotEnough(30, EASY, notEnoughSeries(EASY_GROUPS));
		// ED of 2000: anime ranks 1-10, 10 themes in 3 groups.
		assertThat(groups(pool("ED", 2000, 2000, 100))).hasSize(3);
		assertNotEnough(5, new DrawFilter(Set.of("ED"), 2000, 2000, "easy"), notEnoughSeries(3));
		// Nothing matches: zero groups, still counted in series.
		assertNotEnough(5, new DrawFilter(null, 2011, 2011, "easy"), notEnoughSeries(0));
	}

	@Test
	void normalAndHardCountSongsAndMayRepeatAGroup() {
		Set<Integer> ed2000 = pool("ED", 2000, 2000, 100);
		assertThat(ed2000).hasSize(10);
		for (String tier : new String[] {null, "normal", "hard"}) {
			DrawFilter filter = new DrawFilter(Set.of("ED"), 2000, 2000, tier);
			assertNotEnough(11, filter, notEnough(10));
			Game game = new Game(3);
			game.room.start(10, filter);
			List<Integer> drawn = game.playToGameOver(10);
			assertThat(new TreeSet<>(drawn)).as("tier " + tier).containsExactlyElementsOf(ed2000);
			assertThat(groups(new TreeSet<>(drawn))).as("10 songs from 3 groups").hasSize(3);
		}
		// Without a tier: all 240 themes (anime ranks 1-120), more than enough.
		Game normal = new Game(4);
		normal.room.start(30, DrawFilter.NONE);
		assertThat(normal.outbox.all()).extracting(Sent::type).containsExactly("prepare");
	}

}
