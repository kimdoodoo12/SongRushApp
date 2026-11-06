package com.songrush.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.songrush.catalog.Catalog;
import com.songrush.catalog.CatalogLoader;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Writes a small catalog.json and audio-cache/index.json (with files of the listed sizes) into a
// temp folder and loads it with CatalogLoader.load. Anime i has id 1000 + i and theme 5000 + i;
// every title, song and artist names its anime's number, so each string is unique to one anime.
// Anime 0..drawable-1 have group 9000 + i; the next nullGroup anime have group null. All themes are
// in the draw set and cached. Non-ASCII titles are built from code points (ASCII-only source).
final class GameFixture {

	static final int ANIME_BASE = 1000;

	static final int THEME_BASE = 5000;

	static final int GROUP_BASE = 9000;

	static final long NULL_GROUP_DURATION_MS = 70000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// "sora" in katakana and "haneul" in Hangul.
	private static final String JA = new String(new char[] {(char) 0x30BD, (char) 0x30E9});

	private static final String KO = new String(new char[] {(char) 0xD558, (char) 0xB298});

	private GameFixture() {
	}

	static int animeId(int i) {
		return ANIME_BASE + i;
	}

	static int themeId(int i) {
		return THEME_BASE + i;
	}

	static String basename(int i) {
		return "SkyGarden" + i + "-OP1.ogg";
	}

	static List<Integer> themeIds(int from, int toExclusive) {
		List<Integer> ids = new ArrayList<>();
		for (int i = from; i < toExclusive; i++) {
			ids.add(themeId(i));
		}
		return ids;
	}

	static Catalog build(Path dir, long[] drawableDurationsMs, int nullGroup) {
		int total = drawableDurationsMs.length + nullGroup;
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int i = 0; i < total; i++) {
				boolean grouped = i < drawableDurationsMs.length;
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of(KO + " " + i));
				titles.put("en", List.of("Sky Garden " + i));
				titles.put("ja", List.of(JA + i));
				titles.put("romaji", List.of("Sora no Niwa " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", animeId(i));
				a.put("anilistId", 100000 + i);
				a.put("group", grouped ? GROUP_BASE + i : null);
				a.put("titles", titles);
				a.put("reveal", KO + " " + i);
				anime.add(a);

				if (grouped) {
					LinkedHashSet<String> accepted = new LinkedHashSet<>();
					titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
					groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));
				}

				int size = 10 + i;
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", themeId(i));
				t.put("animeId", animeId(i));
				t.put("type", "OP");
				t.put("slug", "OP1");
				t.put("song", "Garden Song " + i);
				t.put("artists", List.of("Singer " + i + " One", "Band " + i + " Two"));
				t.put("audio", Map.of("basename", basename(i), "size", size));
				themes.add(t);
				drawSet.add(themeId(i));

				long duration = grouped ? drawableDurationsMs[i] : NULL_GROUP_DURATION_MS;
				index.put(Integer.toString(themeId(i)),
						Map.of("basename", basename(i), "durationMs", duration, "size", size));
				Files.write(cache.resolve(basename(i)), new byte[size]);
			}
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
			Catalog catalog = CatalogLoader.load(catalogJson, indexJson);
			if (catalog.candidates().size() != total) {
				throw new IllegalStateException("fixture expected " + total + " candidates, got " + catalog);
			}
			return catalog;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
