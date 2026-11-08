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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 096 (B-022) criteria 1 and 2: easy draws series groups uniformly, and within a chosen group
// every matching theme uniformly (docs/cycles/096-easy-uniform-series.md). One catalog, generated
// once into a static @TempDir: 23 anime (index a, id ANIME_BASE + a, anime rank a + 1, all within
// easy). The four anime BIG_ANIME share series group BIG_GROUP and have 10 cached themes each
// (OP1-OP5, ED1-ED5), 40 themes in all; each of the other 19 anime has one theme (OP1) and a series
// group of its own. 4,000 seeded easy games of 5 songs are played once; with uniform series each of
// the 20 groups is in 5/20 = 25% of games.
class RoomEasyUniformTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int ANIME_BASE = 110000;

	private static final int THEME_BASE = 210000;

	private static final int GROUP_BASE = 310000;

	private static final int BIG_GROUP = GROUP_BASE;

	private static final long DURATION_MS = 60000;

	private static final int ANIME_COUNT = 23;

	// Spread through the draw set so the big group is not one block of candidates.
	private static final Set<Integer> BIG_ANIME = Set.of(0, 6, 12, 18);

	private static final int BIG_THEMES_PER_ANIME = 10;

	private static final int GROUP_COUNT = 20;

	private static final int SONGS = 5;

	private static final int GAMES = 4000;

	private static final double MIN_SHARE = 0.18;

	private static final double MAX_SHARE = 0.32;

	private static final DrawFilter EASY = new DrawFilter(null, null, null, "easy");

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	static Path dir;

	private static Catalog catalog;

	// Theme ids of every game, in song order, for seeds 0 .. GAMES - 1.
	private static List<List<Integer>> games;

	@BeforeAll
	static void playGames() {
		catalog = build(dir);
		assertThat(catalog.candidates()).hasSize(BIG_ANIME.size() * BIG_THEMES_PER_ANIME + ANIME_COUNT - BIG_ANIME.size());
		games = new ArrayList<>();
		for (long seed = 0; seed < GAMES; seed++) {
			Game game = new Game(seed);
			game.room.start(SONGS, EASY);
			games.add(game.playToGameOver(SONGS));
		}
	}

	// ---- fixture ----

	private static int themesOf(int a) {
		return BIG_ANIME.contains(a) ? BIG_THEMES_PER_ANIME : 1;
	}

	// Single-theme anime get groups GROUP_BASE + 1 .. GROUP_BASE + 19 in anime order.
	private static int groupOf(int a) {
		if (BIG_ANIME.contains(a)) {
			return BIG_GROUP;
		}
		int before = 0;
		for (int b = 0; b < a; b++) {
			if (!BIG_ANIME.contains(b)) {
				before++;
			}
		}
		return GROUP_BASE + 1 + before;
	}

	private static int themeId(int a, int j) {
		return THEME_BASE + 100 * a + j;
	}

	private static int animeOf(int themeId) {
		return (themeId - THEME_BASE) / 100;
	}

	private static int groupOfTheme(int themeId) {
		return groupOf(animeOf(themeId));
	}

	private static String type(int j) {
		return j < 5 ? "OP" : "ED";
	}

	private static String slug(int j) {
		return type(j) + (j % 5 + 1);
	}

	private static Set<Integer> bigGroupThemes() {
		Set<Integer> out = new TreeSet<>();
		for (int a : BIG_ANIME) {
			for (int j = 0; j < BIG_THEMES_PER_ANIME; j++) {
				out.add(themeId(a, j));
			}
		}
		return out;
	}

	private static Set<Integer> allGroups() {
		Set<Integer> out = new TreeSet<>();
		for (int a = 0; a < ANIME_COUNT; a++) {
			out.add(groupOf(a));
		}
		return out;
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
				titles.put("en", List.of("Uniform Anime " + a));
				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", ANIME_BASE + a);
				an.put("group", groupOf(a));
				an.put("titles", titles);
				an.put("reveal", "Uniform Reveal " + a);
				an.put("year", 2000 + a);
				anime.add(an);
				accepted.computeIfAbsent(groupOf(a), g -> new ArrayList<>()).add(Normalize.normalize("Uniform Anime " + a));

				for (int j = 0; j < themesOf(a); j++) {
					int id = themeId(a, j);
					String basename = "Uniform" + a + "-" + slug(j) + ".ogg";
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", id);
					t.put("animeId", ANIME_BASE + a);
					t.put("type", type(j));
					t.put("slug", slug(j));
					t.put("song", "Uniform Song " + a + "-" + j);
					t.put("artists", List.of("Uniform Singer " + a));
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
				drawn.add(currentThemeId());
				room.ready(MINA.id(), k);
				room.skip(MINA.id(), k);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			return drawn;
		}

	}

	// ---- fixture sanity ----

	@Test
	void theFixtureHasOneBigGroupAndNineteenSingleThemeGroupsAllInEasy() {
		assertThat(bigGroupThemes()).hasSize(40);
		assertThat(allGroups()).hasSize(GROUP_COUNT);
		for (int a = 0; a < ANIME_COUNT; a++) {
			assertThat(catalog.anime(ANIME_BASE + a).orElseThrow().group()).isEqualTo(groupOf(a));
			assertThat(catalog.animeRank(themeId(a, 0))).as("anime rank of anime " + a).isEqualTo(a + 1)
					.isLessThanOrEqualTo(DrawFilter.EASY_LAST_ANIME);
		}
		assertThat(games).hasSize(GAMES);
		for (int i = 0; i < GAMES; i++) {
			List<Integer> groups = games.get(i).stream().map(RoomEasyUniformTest::groupOfTheme).toList();
			assertThat(groups).as("seed " + i + ": series groups of the game").hasSize(SONGS).doesNotHaveDuplicates();
		}
	}

	// ---- criterion 1 ----

	@Test
	void everySeriesGroupIsInAboutAQuarterOfGames() {
		Map<Integer, Integer> gamesWithGroup = new TreeMap<>();
		allGroups().forEach(g -> gamesWithGroup.put(g, 0));
		for (List<Integer> drawn : games) {
			new TreeSet<>(drawn.stream().map(RoomEasyUniformTest::groupOfTheme).toList())
					.forEach(g -> gamesWithGroup.merge(g, 1, Integer::sum));
		}
		double bigShare = gamesWithGroup.get(BIG_GROUP) / (double) GAMES;
		assertThat(bigShare).as("share of games with the 40-theme group " + BIG_GROUP + " (expected 0.25); all counts "
				+ gamesWithGroup).isBetween(MIN_SHARE, MAX_SHARE);
		gamesWithGroup.forEach((group, count) -> assertThat(count / (double) GAMES)
				.as("share of games with group " + group + " (expected 0.25); all counts " + gamesWithGroup)
				.isBetween(MIN_SHARE, MAX_SHARE));
	}

	// ---- criterion 2 ----

	@Test
	void everyThemeOfTheBigGroupIsChosenAtSomePoint() {
		Set<Integer> seen = new TreeSet<>();
		for (List<Integer> drawn : games) {
			drawn.stream().filter(id -> groupOfTheme(id) == BIG_GROUP).forEach(seen::add);
		}
		assertThat(seen).as("themes of the big group drawn over " + GAMES + " games")
				.containsExactlyElementsOf(bigGroupThemes());
	}

}
