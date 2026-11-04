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

// Cycle 063 (B-012) criterion 2: Room.start with a popularity tier, on the cycle 036 fakes and
// catalogs generated once per class into a static @TempDir (1-byte audio files, spec review).
// Entry i is anime ANIME_BASE + i with one theme THEME_BASE + i at draw-set position i, so its rank
// is i + 1. The main catalog has 1,500 cached entries, even i OP and odd i ED, of year
// 2000 + (i % 20). The small catalog has 510 entries whose rank-2 theme has no cached audio
// (docs/cycles/063-tier-filter.md).
class RoomTierTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int ANIME_BASE = 100000;

	private static final int THEME_BASE = 200000;

	private static final int GROUP_BASE = 300000;

	private static final long DURATION_MS = 60000;

	private static final int MAIN_COUNT = 1500;

	private static final int SMALL_COUNT = 510;

	// Draw-set index (0-based) of the uncached theme in the small catalog: rank 2.
	private static final int UNCACHED = 1;

	private static final int MAX_SONGS = 30;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	static Path dir;

	private static Catalog main;

	private static Catalog small;

	@BeforeAll
	static void buildCatalogs() {
		main = build(dir.resolve("main"), MAIN_COUNT, RoomTierTest::mainYear, i -> true);
		assertThat(main.drawSet()).hasSize(MAIN_COUNT);
		assertThat(main.candidates()).hasSize(MAIN_COUNT);
		small = build(dir.resolve("small"), SMALL_COUNT, RoomTierTest::smallYear, i -> i != UNCACHED);
		assertThat(small.drawSet()).hasSize(SMALL_COUNT);
		assertThat(small.candidates()).hasSize(SMALL_COUNT - 1);
		assertThat(small.candidate(THEME_BASE + UNCACHED)).isEmpty();
	}

	// ---- fixture ----

	private static String type(int i) {
		return i % 2 == 0 ? "OP" : "ED";
	}

	private static int mainYear(int i) {
		return 2000 + (i % 20);
	}

	// Ranks 299, 300 and 301 are of 1999; every other entry is of 2000.
	private static int smallYear(int i) {
		return i == 298 || i == 299 || i == 300 ? 1999 : 2000;
	}

	private static Catalog build(Path folder, int count, IntUnaryOperator year, IntPredicate cached) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(folder.resolve("audio-cache"));
			for (int i = 0; i < count; i++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of("Tier Ko " + i));
				titles.put("en", List.of("Tier Anime " + i));
				titles.put("ja", List.of("Tier Ja " + i));
				titles.put("romaji", List.of("Tier Romaji " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 400000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Tier Reveal " + i);
				a.put("year", year.applyAsInt(i));
				anime.add(a);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

				String basename = "Tier" + i + "-" + type(i) + "1.ogg";
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", THEME_BASE + i);
				t.put("animeId", ANIME_BASE + i);
				t.put("type", type(i));
				t.put("slug", type(i) + "1");
				t.put("song", "Tier Song " + i);
				t.put("artists", List.of("Tier Singer " + i));
				t.put("audio", Map.of("basename", basename, "size", 1));
				themes.add(t);
				drawSet.add(THEME_BASE + i);

				if (cached.test(i)) {
					index.put(Integer.toString(THEME_BASE + i),
							Map.of("basename", basename, "durationMs", DURATION_MS, "size", 1));
					Files.write(cache.resolve(basename), new byte[1]);
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
		// Returns the drawn ranks in song order.
		List<Integer> playToGameOver(int count) {
			List<Integer> drawn = new ArrayList<>();
			for (int k = 0; k < count; k++) {
				assertThat(room.phase()).as("song " + k).isEqualTo(Room.Phase.PREPARE);
				assertThat(room.songIndex()).isEqualTo(k);
				Theme theme = currentTheme();
				int rank = catalog.drawSet().indexOf(theme.id()) + 1;
				assertThat(rank).as("rank of the drawn theme").isEqualTo(theme.id() - THEME_BASE + 1);
				drawn.add(rank);
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

	// Plays one game per seed and returns every rank drawn; checks each game has no repeats.
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

	// Main-catalog ranks allowed by the parts of a filter, worked out from the fixture model.
	private static Set<Integer> expectedMain(String type, Integer year, int fromRank, int toRank) {
		Set<Integer> out = new TreeSet<>();
		for (int i = 0; i < MAIN_COUNT; i++) {
			int rank = i + 1;
			if (rank < fromRank || rank > toRank) {
				continue;
			}
			if (type != null && !type.equals(type(i))) {
				continue;
			}
			if (year != null && mainYear(i) != year) {
				continue;
			}
			out.add(rank);
		}
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

	private static Map<String, Object> notEnough(int available) {
		return Map.of("type", "error", "code", "not-enough-songs", "available", available);
	}

	// ---- fixture sanity ----

	@Test
	void ranksFollowTheDrawSetOrder() {
		for (int i : new int[] {0, 1, 299, 300, 499, 500, 1499}) {
			assertThat(main.drawSet().indexOf(THEME_BASE + i) + 1).isEqualTo(i + 1);
			assertThat(main.theme(THEME_BASE + i).orElseThrow().type()).isEqualTo(type(i));
			assertThat(main.anime(ANIME_BASE + i).orElseThrow().year()).isEqualTo(mainYear(i));
		}
		assertThat(expectedMain("ED", null, 1, 300)).hasSize(150);
	}

	// ---- tier alone ----

	@Test
	void easyOnlyEverDrawsRanksOneToThreeHundredAndCanDrawEachOfThem() {
		Set<Integer> seen = drawMany(main, new DrawFilter(null, null, null, "easy"), MAX_SONGS, 200);
		assertThat(seen).allSatisfy(rank -> assertThat(rank).isBetween(1, 300));
		assertThat(seen).as("every easy rank is drawn at some point").containsExactlyElementsOf(expectedMain(null, null,
				1, 300));
	}

	@Test
	void hardOnlyEverDrawsRanksFiveHundredOneToFifteenHundred() {
		Set<Integer> seen = drawMany(main, new DrawFilter(null, null, null, "hard"), MAX_SONGS, 200);
		assertThat(seen).allSatisfy(rank -> assertThat(rank).isBetween(501, 1500));
		// 6,000 draws over 1,000 ranks: nearly all of them come up.
		assertThat(seen.size()).as("hard draws spread over its ranks").isGreaterThan(900);
	}

	@Test
	void theTierBoundaryRanksAreDrawable() {
		// Easy OP of 2000 (i % 20 == 0): ranks 1, 21, ..., 281; easy ED of 2019: ranks 20, 40, ..., 300.
		Set<Integer> easyLow = expectedMain("OP", 2000, 1, 300);
		Set<Integer> easyHigh = expectedMain("ED", 2019, 1, 300);
		assertThat(easyLow).hasSize(15).contains(1);
		assertThat(easyHigh).hasSize(15).contains(300);
		Game low = new Game(main, 11);
		low.room.start(15, new DrawFilter(Set.of("OP"), 2000, 2000, "easy"));
		assertThat(new TreeSet<>(low.playToGameOver(15))).containsExactlyElementsOf(easyLow);
		Game high = new Game(main, 12);
		high.room.start(15, new DrawFilter(Set.of("ED"), 2019, 2019, "easy"));
		assertThat(new TreeSet<>(high.playToGameOver(15))).containsExactlyElementsOf(easyHigh);

		// Hard OP of 2000: ranks 501, 521, ..., 1481; hard ED of 2019: ranks 520, 540, ..., 1500.
		Set<Integer> hardLow = expectedMain("OP", 2000, 501, 1500);
		Set<Integer> hardHigh = expectedMain("ED", 2019, 501, 1500);
		assertThat(hardLow).hasSize(50).contains(501);
		assertThat(hardHigh).hasSize(50).contains(1500);
		assertThat(drawMany(main, new DrawFilter(Set.of("OP"), 2000, 2000, "hard"), MAX_SONGS, 100))
				.containsExactlyElementsOf(hardLow);
		assertThat(drawMany(main, new DrawFilter(Set.of("ED"), 2019, 2019, "hard"), MAX_SONGS, 100))
				.containsExactlyElementsOf(hardHigh);
	}

	@Test
	void withoutATierRanksOutsideBothTiersAreDrawnToo() {
		Set<Integer> seen = drawMany(main, new DrawFilter(null, null, null, null), MAX_SONGS, 50);
		assertThat(seen).anySatisfy(rank -> assertThat(rank).isBetween(301, 500));
		assertThat(seen).anySatisfy(rank -> assertThat(rank).isBetween(1, 300));
		assertThat(seen).anySatisfy(rank -> assertThat(rank).isBetween(501, 1500));
	}

	// ---- tier combined with types ----

	@Test
	void easyWithEdDrawsOnlyEdThemesRankedOneToThreeHundred() {
		DrawFilter filter = new DrawFilter(Set.of("ED"), null, null, "easy");
		Set<Integer> seen = drawMany(main, filter, MAX_SONGS, 100);
		for (int rank : seen) {
			assertThat(rank).isBetween(1, 300);
			assertThat(main.theme(THEME_BASE + rank - 1).orElseThrow().type()).as("rank " + rank).isEqualTo("ED");
		}
		// The drawable set is exactly the 150 ED themes of ranks 1-300.
		assertThat(seen).hasSize(150).containsExactlyElementsOf(expectedMain("ED", null, 1, 300));
	}

	// ---- available ----

	@Test
	void availableCountsTheCandidatesLeftAfterBothChecks() {
		// Easy ED of 2001 (i % 20 == 1): ranks 2, 22, ..., 282.
		assertThat(expectedMain("ED", 2001, 1, 300)).hasSize(15);
		Game tooMany = new Game(main, 20);
		tooMany.room.start(16, new DrawFilter(Set.of("ED"), 2001, 2001, "easy"));
		assertThat(tooMany.outbox.all()).extracting(Sent::message).containsExactly(notEnough(15));
		assertThat(tooMany.room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(tooMany.scheduler.pending()).isZero();

		Game enough = new Game(main, 20);
		enough.room.start(15, new DrawFilter(Set.of("ED"), 2001, 2001, "easy"));
		assertThat(new TreeSet<>(enough.playToGameOver(15))).containsExactlyElementsOf(expectedMain("ED", 2001, 1, 300));

		// Hard ED of 2000: none (ED is odd, i % 20 == 0 is even).
		Game none = new Game(main, 21);
		none.room.start(5, new DrawFilter(Set.of("ED"), 2000, 2000, "hard"));
		assertThat(none.outbox.all()).extracting(Sent::message).containsExactly(notEnough(0));

		// Without a tier the same ED of 2001 has 75 themes, so 30 can be drawn.
		Game normal = new Game(main, 22);
		normal.room.start(MAX_SONGS, new DrawFilter(Set.of("ED"), 2001, 2001, null));
		assertThat(normal.outbox.all()).extracting(Sent::type).containsExactly("settings", "prepare");
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

	// ---- an uncached theme keeps its rank ----

	@Test
	void anUncachedThemeKeepsItsPositionSoLaterRanksDoNotShift() {
		assertThat(small.drawSet().indexOf(THEME_BASE + 2) + 1).as("rank-3 theme").isEqualTo(3);
		assertThat(small.candidates().get(1).themeId()).as("second candidate is the rank-3 theme")
				.isEqualTo(THEME_BASE + 2);

		// Hard: ranks 501..510, the last ten draw-set entries. Shifted ranks would leave only nine.
		Game hardTooMany = new Game(small, 40);
		hardTooMany.room.start(11, new DrawFilter(null, null, null, "hard"));
		assertThat(hardTooMany.outbox.all()).extracting(Sent::message).containsExactly(notEnough(10));
		Game hard = new Game(small, 40);
		hard.room.start(10, new DrawFilter(null, null, null, "hard"));
		assertThat(new TreeSet<>(hard.playToGameOver(10)))
				.containsExactly(501, 502, 503, 504, 505, 506, 507, 508, 509, 510);

		// Easy of 1999: ranks 299 and 300 (rank 301 is out). Shifted ranks would let rank 301 in.
		Game easyTooMany = new Game(small, 41);
		easyTooMany.room.start(5, new DrawFilter(null, 1999, 1999, "easy"));
		assertThat(easyTooMany.outbox.all()).extracting(Sent::message).containsExactly(notEnough(2));

		// Easy alone: ranks 1 and 3..300, so rank 3 is drawable and rank 2 never is.
		Set<Integer> seen = drawMany(small, new DrawFilter(null, null, null, "easy"), MAX_SONGS, 200);
		assertThat(seen).doesNotContain(2).contains(1, 3, 300);
		assertThat(seen).allSatisfy(rank -> assertThat(rank).isBetween(1, 300));
	}

}
