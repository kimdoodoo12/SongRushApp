package com.songrush.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;
import com.songrush.cover.CoverMap;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 100 (D-111): writes a catalog.json, audio-cache/index.json with its audio files, and a disk
// cover-cache/index.json with its cover files into a temp folder, and loads both (as GameFixture
// and RoomCoverTest do). Spec a (0-based) is anime ANIME_BASE + a with anime rank a + 1 (the draw set
// lists the anime in spec order), series group GROUP_BASE + spec.group, and spec.themes cached
// themes THEME_BASE + 100 * a + j of DURATION_MS each, of the spec's type. Only an anime with
// spec.cover has a cover, with id coverId(a). Its one title "Choice Anime <a>" is unique to it.
final class ChoiceFixture {

	static final int ANIME_BASE = 700000;

	static final int THEME_BASE = 800000;

	static final int GROUP_BASE = 900000;

	static final long DURATION_MS = 60000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// group is relative to GROUP_BASE; year null leaves the key out.
	record Spec(int group, int themes, String type, Integer year, boolean cover) {
	}

	// The loaded catalog and cover map, with lookups back to the spec index.
	record Built(Catalog catalog, CoverMap covers, List<Spec> specs) {

		int animeIndexOfTheme(int themeId) {
			return (themeId - THEME_BASE) / 100;
		}

		int groupOf(int a) {
			return specs.get(a).group();
		}

		// The spec index named by a "/cover/<id>" URL, or -1.
		int animeIndexOfCoverUrl(String url) {
			for (int a = 0; a < specs.size(); a++) {
				if (specs.get(a).cover() && coverUrl(a).equals(url)) {
					return a;
				}
			}
			return -1;
		}

	}

	private ChoiceFixture() {
	}

	static Spec covered(int group) {
		return new Spec(group, 1, "OP", 2000, true);
	}

	static Spec uncovered(int group) {
		return new Spec(group, 1, "OP", 2000, false);
	}

	// n covered anime, each with one OP and a group of its own, groups from firstGroup.
	static List<Spec> coveredSingles(int firstGroup, int n) {
		List<Spec> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(covered(firstGroup + i));
		}
		return out;
	}

	static List<Spec> uncoveredSingles(int firstGroup, int n) {
		List<Spec> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(uncovered(firstGroup + i));
		}
		return out;
	}

	static int animeId(int a) {
		return ANIME_BASE + a;
	}

	static int themeId(int a, int j) {
		return THEME_BASE + 100 * a + j;
	}

	static String coverId(int a) {
		return "ChoiceCover" + a + "x";
	}

	static String coverUrl(int a) {
		return "/cover/" + coverId(a);
	}

	static String title(int a) {
		return "Choice Anime " + a;
	}

	static Built build(Path dir, List<Spec> specs) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<Integer, List<String>> accepted = new TreeMap<>();
		Map<String, Object> index = new LinkedHashMap<>();
		List<String> coverEntries = new ArrayList<>();
		int candidates = 0;
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			Path coverCache = Files.createDirectories(dir.resolve("cover-cache"));
			for (int a = 0; a < specs.size(); a++) {
				Spec spec = specs.get(a);
				int group = GROUP_BASE + spec.group();
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("en", List.of(title(a)));
				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", animeId(a));
				an.put("group", group);
				an.put("titles", titles);
				an.put("reveal", "Choice Reveal " + a);
				if (spec.year() != null) {
					an.put("year", spec.year());
				}
				anime.add(an);
				accepted.computeIfAbsent(group, g -> new ArrayList<>()).add(Normalize.normalize(title(a)));

				for (int j = 0; j < spec.themes(); j++) {
					int id = themeId(a, j);
					String slug = spec.type() + (j + 1);
					String basename = "Choice" + a + "-" + slug + ".ogg";
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", id);
					t.put("animeId", animeId(a));
					t.put("type", spec.type());
					t.put("slug", slug);
					t.put("song", "Choice Song " + a + "-" + j);
					t.put("artists", List.of("Choice Singer " + a));
					t.put("audio", Map.of("basename", basename, "size", 1));
					themes.add(t);
					drawSet.add(id);
					index.put(Integer.toString(id), Map.of("basename", basename, "durationMs", DURATION_MS, "size", 1));
					Files.write(cache.resolve(basename), new byte[1]);
					candidates++;
				}
				if (spec.cover()) {
					int size = 7;
					Files.write(coverCache.resolve(coverId(a) + ".jpg"), new byte[size]);
					coverEntries.add("\"%d\": {\"id\": \"%s\", \"file\": \"%s.jpg\", \"size\": %d}"
							.formatted(animeId(a), coverId(a), coverId(a), size));
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
			Path catalogJson = dir.resolve("catalog.json");
			Files.writeString(catalogJson, MAPPER.writeValueAsString(root));
			Path indexJson = cache.resolve("index.json");
			Files.writeString(indexJson, MAPPER.writeValueAsString(index));
			Files.writeString(coverCache.resolve("index.json"),
					"{\"version\": 1, \"covers\": {" + String.join(", ", coverEntries) + "}}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Catalog catalog = CatalogLoader.load(dir.resolve("catalog.json"), dir.resolve("audio-cache").resolve("index.json"));
		if (catalog.candidates().size() != candidates) {
			throw new IllegalStateException("fixture expected " + candidates + " candidates, got " + catalog);
		}
		CoverMap covers = CoverMap.loadDisk(dir.resolve("cover-cache"));
		int expectedCovers = (int) specs.stream().filter(Spec::cover).count();
		if (covers.size() != expectedCovers) {
			throw new IllegalStateException("fixture expected " + expectedCovers + " covers, got " + covers);
		}
		return new Built(catalog, covers, List.copyOf(specs));
	}

}
