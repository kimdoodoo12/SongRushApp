package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 094 (B-021) criterion 1: Catalog.animeRank is the 1-based rank of a theme's anime, counting
// distinct anime in drawSet order by first appearance, the same for every theme of the anime, 0 for a
// theme outside the draw set, and computed from drawSet whether the theme is cached or not
// (docs/cycles/094-nested-tiers.md). Catalogs are written into a temp folder. Theme id
// THEME_BASE + 10 * a + j is theme j of anime a (anime id ANIME_BASE + a).
class CatalogAnimeRankTest {

	private static final int ANIME_BASE = 100;

	private static final int THEME_BASE = 10000;

	private static final int GROUP_BASE = 500;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private static int themeId(int a, int j) {
		return THEME_BASE + 10 * a + j;
	}

	private static String basename(int themeId) {
		return "Rank" + themeId + ".ogg";
	}

	// themesPerAnime[a] themes for anime a; drawSet lists theme ids in order; cached theme ids get an
	// index entry and a 1-byte audio file.
	private Catalog build(String name, int[] themesPerAnime, List<Integer> drawSet, Set<Integer> cached) {
		Path folder = dir.resolve(name);
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(folder.resolve("audio-cache"));
			for (int a = 0; a < themesPerAnime.length; a++) {
				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", ANIME_BASE + a);
				an.put("group", GROUP_BASE + a);
				an.put("titles", Map.of("en", List.of("Rank Anime " + a)));
				an.put("reveal", "Rank Anime " + a);
				an.put("year", 2000);
				anime.add(an);
				groups.add(Map.of("id", GROUP_BASE + a, "accepted", List.of("rankanime" + a)));
				for (int j = 0; j < themesPerAnime[a]; j++) {
					int id = themeId(a, j);
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", id);
					t.put("animeId", ANIME_BASE + a);
					t.put("type", j % 2 == 0 ? "OP" : "ED");
					t.put("slug", "OP" + (j + 1));
					t.put("song", "Rank Song " + id);
					t.put("artists", List.of());
					t.put("audio", Map.of("basename", basename(id), "size", 1));
					themes.add(t);
					if (cached.contains(id)) {
						index.put(Integer.toString(id), Map.of("basename", basename(id), "durationMs", 60000, "size", 1));
						Files.write(cache.resolve(basename(id)), new byte[1]);
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
			Files.writeString(catalogJson, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
			Path indexJson = cache.resolve("index.json");
			Files.writeString(indexJson, MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
			return CatalogLoader.load(catalogJson, indexJson);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	void everyThemeOfAnAnimeHasItsAnimesRank() {
		// Anime 0 has 3 themes, anime 1 has 1, anime 2 has 2, anime 3 has 1: draw-set positions 1-7.
		List<Integer> drawSet = List.of(themeId(0, 0), themeId(0, 1), themeId(0, 2), themeId(1, 0), themeId(2, 0),
				themeId(2, 1), themeId(3, 0));
		Catalog catalog = build("small", new int[] {3, 1, 2, 1}, drawSet, Set.copyOf(drawSet));
		assertThat(catalog.candidates()).hasSize(7);

		assertThat(catalog.animeRank(themeId(0, 0))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(0, 1))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(0, 2))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(1, 0))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(2, 0))).isEqualTo(3);
		assertThat(catalog.animeRank(themeId(2, 1))).isEqualTo(3);
		assertThat(catalog.animeRank(themeId(3, 0))).isEqualTo(4);

		// The theme rank is unchanged: the draw-set position.
		for (int i = 0; i < drawSet.size(); i++) {
			assertThat(catalog.rank(drawSet.get(i))).isEqualTo(i + 1);
		}
	}

	@Test
	void aThemeOutsideTheDrawSetIsZero() {
		// Anime 0's third theme and anime 2 are in the catalog but not in the draw set.
		List<Integer> drawSet = List.of(themeId(0, 0), themeId(0, 1), themeId(1, 0), themeId(3, 0));
		Catalog catalog = build("outside", new int[] {3, 1, 1, 1}, drawSet, Set.copyOf(drawSet));
		assertThat(catalog.theme(themeId(0, 2))).isPresent();
		assertThat(catalog.theme(themeId(2, 0))).isPresent();

		assertThat(catalog.animeRank(themeId(0, 2))).as("theme of a ranked anime, not in the draw set").isZero();
		assertThat(catalog.animeRank(themeId(2, 0))).as("anime with no draw-set theme").isZero();
		assertThat(catalog.animeRank(999999)).as("unknown theme id").isZero();
		assertThat(catalog.animeRank(0)).isZero();
		assertThat(catalog.animeRank(-1)).isZero();
		// Anime 2 has no draw-set theme, so it takes no rank: anime 3 is the third.
		assertThat(catalog.animeRank(themeId(0, 0))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(1, 0))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(3, 0))).isEqualTo(3);
	}

	@Test
	void uncachedThemesStillCountInTheRanking() {
		// Anime 0 (both themes) and anime 2's first theme have no cached audio.
		List<Integer> drawSet = List.of(themeId(0, 0), themeId(0, 1), themeId(1, 0), themeId(2, 0), themeId(2, 1),
				themeId(3, 0));
		Catalog catalog = build("uncached", new int[] {2, 1, 2, 1}, drawSet,
				Set.of(themeId(1, 0), themeId(2, 1), themeId(3, 0)));
		assertThat(catalog.candidates()).extracting(Candidate::themeId)
				.containsExactly(themeId(1, 0), themeId(2, 1), themeId(3, 0));

		assertThat(catalog.animeRank(themeId(0, 0))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(0, 1))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(1, 0))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(2, 0))).isEqualTo(3);
		assertThat(catalog.animeRank(themeId(2, 1))).isEqualTo(3);
		assertThat(catalog.animeRank(themeId(3, 0))).isEqualTo(4);
	}

	@Test
	void withoutAnyAudioIndexTheRanksAreTheSame() {
		List<Integer> drawSet = List.of(themeId(0, 0), themeId(1, 0), themeId(1, 1), themeId(2, 0));
		Catalog catalog = build("none", new int[] {1, 2, 1}, drawSet, Set.of());
		assertThat(catalog.candidates()).isEmpty();
		assertThat(catalog.animeRank(themeId(0, 0))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(1, 0))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(1, 1))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(2, 0))).isEqualTo(3);
	}

	@Test
	void anAnimeIsRankedByItsFirstAppearance() {
		// Anime 0's second theme comes after anime 1's: it keeps anime 0's rank, and anime 2 is third.
		List<Integer> drawSet = List.of(themeId(0, 0), themeId(1, 0), themeId(0, 1), themeId(2, 0));
		Catalog catalog = build("first", new int[] {2, 1, 1}, drawSet, Set.copyOf(drawSet));
		assertThat(catalog.animeRank(themeId(0, 0))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(1, 0))).isEqualTo(2);
		assertThat(catalog.animeRank(themeId(0, 1))).isEqualTo(1);
		assertThat(catalog.animeRank(themeId(2, 0))).isEqualTo(3);
	}

	@Test
	void aLargeDrawSetRanksEveryAnimeInOrder() {
		// 400 anime with 1-3 themes each: anime rank and theme position diverge past rank 100 and 300.
		int count = 400;
		int[] perAnime = new int[count];
		List<Integer> drawSet = new ArrayList<>();
		for (int a = 0; a < count; a++) {
			perAnime[a] = 1 + (a % 3);
			for (int j = 0; j < perAnime[a]; j++) {
				drawSet.add(themeId(a, j));
			}
		}
		Catalog catalog = build("large", perAnime, drawSet, Set.copyOf(drawSet));
		for (int a = 0; a < count; a++) {
			for (int j = 0; j < perAnime[a]; j++) {
				assertThat(catalog.animeRank(themeId(a, j))).as("anime " + a + " theme " + j).isEqualTo(a + 1);
			}
		}
		assertThat(catalog.rank(themeId(99, 0))).as("anime 100 starts past position 100").isGreaterThan(100);
		assertThat(catalog.animeRank(themeId(99, 0))).isEqualTo(100);
		assertThat(catalog.animeRank(themeId(399, 0))).isEqualTo(400);
	}

}
