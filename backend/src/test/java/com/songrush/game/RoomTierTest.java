package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 063 (B-012) criterion 2, rewritten for cycle 094 (B-021) criteria 2, 4 and 5: Room.start with
// a popularity tier by anime rank (easy 1-100, no tier or normal 1-300, hard every draw-set anime), on
// the cycle 036 fakes and catalogs generated once per class into a static @TempDir (1-byte audio
// files). Anime a (0-based, id ANIME_BASE + a, its own group) has themesOf(a) = 1 + ((a + 1) % 3)
// themes, theme j of id THEME_BASE + 10 * a + j, OP for j == 0 and ED otherwise; the draw set lists
// every theme anime by anime, so anime a has anime rank a + 1 while its draw-set positions run ahead
// of it (anime rank 100 starts at position 199). The main catalog has 360 cached anime of year
// 2000 + (a % 20). The small catalog has 110 anime; anime 1 (rank 2) has no cached audio, and anime
// 99 and 100 (ranks 100 and 101) are of 1999, the rest of 2000 (docs/cycles/094-nested-tiers.md).
class RoomTierTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int ANIME_BASE = 100000;

	private static final int THEME_BASE = 200000;

	private static final int GROUP_BASE = 300000;

	private static final long DURATION_MS = 60000;

	private static final int MAIN_COUNT = 360;

	private static final int SMALL_COUNT = 110;

	// Anime index (0-based) of the uncached anime in the small catalog: rank 2.
	private static final int UNCACHED = 1;

	private static final int MAX_SONGS = 30;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	static Path dir;

	private static Catalog main;

	private static Catalog small;

	@BeforeAll
	static void buildCatalogs() {
		main = build(dir.resolve("main"), MAIN_COUNT, RoomTierTest::mainYear, a -> true);
		assertThat(main.drawSet()).hasSize(themeCount(MAIN_COUNT));
		assertThat(main.candidates()).hasSize(themeCount(MAIN_COUNT));
		small = build(dir.resolve("small"), SMALL_COUNT, RoomTierTest::smallYear, a -> a != UNCACHED);
		assertThat(small.drawSet()).hasSize(themeCount(SMALL_COUNT));
		assertThat(small.candidates()).hasSize(themeCount(SMALL_COUNT) - themesOf(UNCACHED));
	}

	// ---- fixture ----

	private static int themesOf(int a) {
		return 1 + ((a + 1) % 3);
	}

	private static int themeCount(int animeCount) {
		int total = 0;
		for (int a = 0; a < animeCount; a++) {
			total += themesOf(a);
		}
		return total;
	}

	private static int themeId(int a, int j) {
		return THEME_BASE + 10 * a + j;
	}

	private static String type(int j) {
		return j == 0 ? "OP" : "ED";
	}

	// The fixture's anime rank of a theme id, from the model (not from the catalog).
	private static int animeRankOf(int themeId) {
		return (themeId - THEME_BASE) / 10 + 1;
	}

	private static int mainYear(int a) {
		return 2000 + (a % 20);
	}

	private static int smallYear(int a) {
		return a == 99 || a == 100 ? 1999 : 2000;
	}

	private static Catalog build(Path folder, int count, IntUnaryOperator year, IntPredicate cached) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(folder.resolve("audio-cache"));
			for (int a = 0; a < count; a++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of("Tier Ko " + a));
				titles.put("en", List.of("Tier Anime " + a));
				titles.put("ja", List.of("Tier Ja " + a));
				titles.put("romaji", List.of("Tier Romaji " + a));

				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", ANIME_BASE + a);
				an.put("anilistId", 400000 + a);
				an.put("group", GROUP_BASE + a);
				an.put("titles", titles);
				an.put("reveal", "Tier Reveal " + a);
				an.put("year", year.applyAsInt(a));
				anime.add(an);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + a, "accepted", List.copyOf(accepted)));

				for (int j = 0; j < themesOf(a); j++) {
					int id = themeId(a, j);
					String basename = "Tier" + a + "-" + type(j) + (j + 1) + ".ogg";
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", id);
					t.put("animeId", ANIME_BASE + a);
					t.put("type", type(j));
					t.put("slug", type(j) + (j + 1));
					t.put("song", "Tier Song " + a + "-" + j);
					t.put("artists", List.of("Tier Singer " + a));
					t.put("audio", Map.of("basename", basename, "size", 1));
					themes.add(t);
					drawSet.add(id);

					if (cached.test(a)) {
						index.put(Integer.toString(id), Map.of("basename", basename, "durationMs", DURATION_MS, "size", 1));
						Files.write(cache.resolve(basename), new byte[1]);
					}
				}
			}
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

		final Catalog catalog;

		final Room room;

		Game(Catalog catalog, long seed) {
			this.catalog = catalog;
			room = new Room(catalog, tokens, scheduler, clock, new Random(seed), outbox, List.of(MINA));
		}

		Theme currentTheme() {
			String url = (String) outbox.last("prepare").message().get("audioUrl");
			assertThat(url).startsWith("/audio/");
			int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
			return catalog.theme(themeId).orElseThrow();
		}

		// From the first prepare: every song is readied and skipped, then the reveal pause runs.
		// Returns the drawn theme ids in song order.
		List<Integer> playToGameOver(int count) {
			List<Integer> drawn = new ArrayList<>();
			for (int k = 0; k < count; k++) {
				assertThat(room.phase()).as("song " + k).isEqualTo(Room.Phase.PREPARE);
				assertThat(room.songIndex()).isEqualTo(k);
				drawn.add(currentTheme().id());
				room.ready(MINA.id(), k);
				assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
				room.skip(MINA.id(), k);
				assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(outbox.ofType("gameOver")).hasSize(1);
			return drawn;
		}

	}

	// Plays one game per seed and returns every theme id drawn; checks each game has no repeats.
	private static Set<Integer> drawMany(Catalog catalog, DrawFilter filter, int songs, int seeds) {
		Set<Integer> seen = new TreeSet<>();
		for (long seed = 0; seed < seeds; seed++) {
			Game game = new Game(catalog, seed);
			game.room.start(songs, filter);
			List<Integer> drawn = game.playToGameOver(songs);
			assertThat(drawn).as(filter + " seed " + seed).doesNotHaveDuplicates();
			seen.addAll(drawn);
		}
		return seen;
	}

	private static Set<Integer> playOnce(Catalog catalog, DrawFilter filter, int songs, long seed) {
		Game game = new Game(catalog, seed);
		game.room.start(songs, filter);
		List<Integer> drawn = game.playToGameOver(songs);
		assertThat(drawn).doesNotHaveDuplicates();
		return new TreeSet<>(drawn);
	}

	// Main-catalog theme ids allowed by the parts of a filter, worked out from the fixture model.
	// type and the year bounds may be null; anime ranks run from 1 to lastAnimeRank.
	private static Set<Integer> expectedMain(String type, Integer yearFrom, Integer yearTo, int lastAnimeRank) {
		Set<Integer> out = new TreeSet<>();
		for (int a = 0; a < MAIN_COUNT && a + 1 <= lastAnimeRank; a++) {
			if (yearFrom != null && mainYear(a) < yearFrom || yearTo != null && mainYear(a) > yearTo) {
				continue;
			}
			for (int j = 0; j < themesOf(a); j++) {
				if (type == null || type.equals(type(j))) {
					out.add(themeId(a, j));
				}
			}
		}
		return out;
	}

	private static Set<Integer> animeRanks(Set<Integer> themeIds) {
		Set<Integer> out = new TreeSet<>();
		themeIds.forEach(id -> out.add(animeRankOf(id)));
		return out;
	}

	private static Map<String, Object> settings(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "settings");
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	// Normal and hard: available counts songs, and there is no unit field.
	private static Map<String, Object> notEnough(int available) {
		return Map.of("type", "error", "code", "not-enough-songs", "available", available);
	}

	// Easy: available counts series groups.
	private static Map<String, Object> notEnoughSeries(int available) {
		return Map.of("type", "error", "code", "not-enough-songs", "available", available, "unit", "series");
	}

	private static void assertNotEnough(Catalog catalog, int songs, DrawFilter filter, Map<String, Object> error) {
		Game game = new Game(catalog, 99);
		game.room.start(songs, filter);
		assertThat(game.outbox.all()).as(filter + " " + songs + " songs").extracting(Sent::message)
				.containsExactly(error);
		assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(game.scheduler.pending()).isZero();
	}

	// ---- fixture sanity ----

	@Test
	void animeRanksRunBehindDrawSetPositions() {
		// Anime rank 100 (a = 99) has 2 themes at positions 199-200: past position 100, in easy.
		assertThat(themesOf(99)).isEqualTo(2);
		for (int j = 0; j < themesOf(99); j++) {
			assertThat(main.drawSet().indexOf(themeId(99, j)) + 1).isGreaterThan(100);
			assertThat(main.animeRank(themeId(99, j))).isEqualTo(100);
		}
		// Anime rank 101 (a = 100) has 3 themes, all still within draw-set position 300.
		for (int j = 0; j < themesOf(100); j++) {
			assertThat(main.drawSet().indexOf(themeId(100, j)) + 1).isLessThanOrEqualTo(300);
			assertThat(main.animeRank(themeId(100, j))).isEqualTo(101);
		}
		// Anime rank 300 (a = 299) and 301 (a = 300).
		assertThat(main.animeRank(themeId(299, 0))).isEqualTo(300);
		assertThat(main.animeRank(themeId(300, 0))).isEqualTo(301);
		assertThat(main.animeRank(themeId(MAIN_COUNT - 1, 0))).isEqualTo(MAIN_COUNT);
		// Theme rank is the draw-set position, as before.
		assertThat(main.rank(themeId(99, 0))).isEqualTo(main.drawSet().indexOf(themeId(99, 0)) + 1);
		assertThat(main.theme(themeId(99, 1)).orElseThrow().type()).isEqualTo("ED");
		assertThat(main.anime(ANIME_BASE + 99).orElseThrow().year()).isEqualTo(2019);
	}

	// ---- easy: anime ranks 1-100 ----

	@Test
	void easyOnlyEverDrawsAnimeRanksOneToOneHundredAndCanDrawEachOfTheirThemes() {
		Set<Integer> seen = drawMany(main, new DrawFilter(null, null, null, "easy"), MAX_SONGS, 200);
		assertThat(animeRanks(seen)).allSatisfy(rank -> assertThat(rank).isBetween(1, 100));
		assertThat(seen).as("every theme of anime ranks 1-100 is drawn at some point")
				.containsExactlyElementsOf(expectedMain(null, null, null, 100));
	}

	@Test
	void easyBoundaryWithTypeAndYear() {
		// Easy OP of 2019 (a % 20 == 19): anime ranks 20, 40, 60, 80, 100; rank 120 is out.
		Set<Integer> easyOp = expectedMain("OP", 2019, 2019, 100);
		assertThat(animeRanks(easyOp)).containsExactly(20, 40, 60, 80, 100);
		assertThat(playOnce(main, new DrawFilter(Set.of("OP"), 2019, 2019, "easy"), 5, 11))
				.containsExactlyElementsOf(easyOp);
		assertNotEnough(main, 6, new DrawFilter(Set.of("OP"), 2019, 2019, "easy"), notEnoughSeries(5));

		// Easy ED of 2018-2019: 10 themes in 7 anime, among them anime rank 100's ED at position 200.
		Set<Integer> easyEd = expectedMain("ED", 2018, 2019, 100);
		assertThat(easyEd).hasSize(10).contains(themeId(99, 1));
		assertThat(animeRanks(easyEd)).containsExactly(19, 20, 40, 59, 79, 80, 100);
		DrawFilter filter = new DrawFilter(Set.of("ED"), 2018, 2019, "easy");
		assertThat(drawMany(main, filter, 7, 60)).containsExactlyElementsOf(easyEd);
		assertNotEnough(main, 8, filter, notEnoughSeries(7));
	}

	// ---- normal: anime ranks 1-300 ----

	@Test
	void withoutATierOrWithNormalOnlyAnimeRanksOneToThreeHundredAreDrawn() {
		for (String tier : new String[] {null, "normal"}) {
			// OP of 2019: anime ranks 20, 40, ..., 300 (15 of them); 320, 340 and 360 are out.
			Set<Integer> op = expectedMain("OP", 2019, 2019, 300);
			assertThat(animeRanks(op)).hasSize(15).contains(20, 100, 120, 300).doesNotContain(320);
			assertThat(playOnce(main, new DrawFilter(Set.of("OP"), 2019, 2019, tier), 15, 21))
					.as("tier " + tier).containsExactlyElementsOf(op);
			assertNotEnough(main, 16, new DrawFilter(Set.of("OP"), 2019, 2019, tier), notEnough(15));

			// ED of 2019: 15 themes, several of the same anime (no series rule outside easy).
			Set<Integer> ed = expectedMain("ED", 2019, 2019, 300);
			assertThat(ed).hasSize(15);
			assertThat(animeRanks(ed).size()).isLessThan(ed.size());
			assertThat(playOnce(main, new DrawFilter(Set.of("ED"), 2019, 2019, tier), 15, 22))
					.as("tier " + tier).containsExactlyElementsOf(ed);
			assertNotEnough(main, 16, new DrawFilter(Set.of("ED"), 2019, 2019, tier), notEnough(15));
		}

		Set<Integer> seen = drawMany(main, DrawFilter.NONE, MAX_SONGS, 50);
		assertThat(animeRanks(seen)).allSatisfy(rank -> assertThat(rank).isBetween(1, 300));
		assertThat(animeRanks(seen)).anySatisfy(rank -> assertThat(rank).isBetween(101, 300));
		assertThat(animeRanks(seen)).anySatisfy(rank -> assertThat(rank).isBetween(1, 100));
	}

	// ---- hard: every draw-set anime ----

	@Test
	void hardDrawsAnyAnimeIncludingRanksAboveThreeHundred() {
		// Hard OP of 2019: anime ranks 20, 40, ..., 360 (18 of them).
		Set<Integer> op = expectedMain("OP", 2019, 2019, MAIN_COUNT);
		assertThat(animeRanks(op)).hasSize(18).contains(20, 100, 300, 320, 360);
		DrawFilter hardOp = new DrawFilter(Set.of("OP"), 2019, 2019, "hard");
		assertThat(playOnce(main, hardOp, 18, 31)).containsExactlyElementsOf(op);
		assertNotEnough(main, 19, hardOp, notEnough(18));

		// Hard ED of 2000: 18 themes over anime ranks 1-341; one more song than that is not enough,
		// counted in songs.
		Set<Integer> ed = expectedMain("ED", 2000, 2000, MAIN_COUNT);
		assertThat(ed).hasSize(18);
		assertThat(animeRanks(ed)).contains(1, 341);
		assertNotEnough(main, ed.size() + 1, new DrawFilter(Set.of("ED"), 2000, 2000, "hard"), notEnough(ed.size()));

		Set<Integer> seen = drawMany(main, new DrawFilter(null, null, null, "hard"), MAX_SONGS, 100);
		assertThat(animeRanks(seen)).allSatisfy(rank -> assertThat(rank).isBetween(1, MAIN_COUNT));
		assertThat(animeRanks(seen)).anySatisfy(rank -> assertThat(rank).isBetween(1, 100));
		assertThat(animeRanks(seen)).anySatisfy(rank -> assertThat(rank).isBetween(101, 300));
		assertThat(animeRanks(seen)).anySatisfy(rank -> assertThat(rank).isBetween(301, MAIN_COUNT));
	}

	// ---- settings ----

	@Test
	void settingsCarriesTheTierLast() {
		Map<DrawFilter, Map<String, Object>> cases = new LinkedHashMap<>();
		cases.put(new DrawFilter(null, null, null, "easy"), settings("tier", "easy"));
		cases.put(new DrawFilter(null, null, null, "hard"), settings("tier", "hard"));
		cases.put(new DrawFilter(Set.of("ED"), null, null, "easy"), settings("types", List.of("ED"), "tier", "easy"));
		cases.put(new DrawFilter(Set.of("ED", "OP"), 2000, 2019, "hard"),
				settings("types", List.of("OP", "ED"), "yearFrom", 2000, "yearTo", 2019, "tier", "hard"));
		for (Map.Entry<DrawFilter, Map<String, Object>> c : cases.entrySet()) {
			Game game = new Game(main, 30);
			game.room.start(5, c.getKey());
			List<Sent> sent = game.outbox.all();
			assertThat(sent).extracting(Sent::type).as(c.getKey().toString()).containsExactly("settings", "prepare");
			assertThat(sent.get(0).toAll()).isTrue();
			assertThat(sent.get(0).message()).as(c.getKey().toString()).isEqualTo(c.getValue());
			assertThat(new ArrayList<>(sent.get(0).message().keySet()))
					.containsExactlyElementsOf(c.getValue().keySet());
			assertThat(game.room.settings()).isEqualTo(c.getKey());
			game.playToGameOver(5);
			assertThat(game.outbox.ofType("settings")).as("settings once per game").hasSize(1);
		}
	}

	@Test
	void normalSendsNoSettingsAndStartsWithPrepare() {
		Game game = new Game(main, 31);
		game.room.start(5, new DrawFilter(null, null, null, "normal"));
		assertThat(game.outbox.all()).extracting(Sent::type).containsExactly("prepare");
		assertThat(game.room.settings()).isEqualTo(DrawFilter.NONE);
		game.playToGameOver(5);
		assertThat(game.outbox.ofType("settings")).isEmpty();
	}

	// ---- an uncached anime keeps its rank ----

	@Test
	void anUncachedAnimeKeepsItsRankSoLaterRanksDoNotShift() {
		assertThat(small.animeRank(themeId(UNCACHED, 0))).as("uncached anime").isEqualTo(2);
		assertThat(small.animeRank(themeId(2, 0))).isEqualTo(3);
		assertThat(small.animeRank(themeId(100, 0))).isEqualTo(101);
		for (int j = 0; j < themesOf(UNCACHED); j++) {
			assertThat(small.candidate(themeId(UNCACHED, j))).isEmpty();
		}

		// Easy of 1999: only anime rank 100 (one group, 2 themes); shifted ranks would let rank 101 in.
		assertNotEnough(small, 5, new DrawFilter(null, 1999, 1999, "easy"), notEnoughSeries(1));
		// Normal of 1999: anime ranks 100 and 101, 2 + 3 themes.
		assertNotEnough(small, 6, new DrawFilter(null, 1999, 1999, null), notEnough(5));
		assertThat(playOnce(small, new DrawFilter(null, 1999, 1999, null), 5, 41)).containsExactly(themeId(99, 0),
				themeId(99, 1), themeId(100, 0), themeId(100, 1), themeId(100, 2));

		// Easy alone: anime ranks 1 and 3-100, so rank 3 is drawable and rank 2 never is.
		Set<Integer> ranks = animeRanks(drawMany(small, new DrawFilter(null, null, null, "easy"), MAX_SONGS, 100));
		assertThat(ranks).doesNotContain(2, 101).contains(1, 3, 100);
		assertThat(ranks).allSatisfy(rank -> assertThat(rank).isBetween(1, 100));
	}

}
