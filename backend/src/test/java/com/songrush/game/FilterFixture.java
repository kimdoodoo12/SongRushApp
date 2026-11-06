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

// Cycle 060: writes a catalog.json whose entries have a known theme type and anime year, plus
// audio-cache/index.json and the audio files, into a temp folder (as GameFixture does; no static
// resources, spec review H1), and loads it with CatalogLoader.load. Entry i is anime 1000 + i with
// one theme 5000 + i; a grouped entry has group 9000 + i, an ungrouped one group null. A null year
// leaves the "year" key out. Every theme is in the draw set and cached. Titles name the entry's
// number, so each string is unique to one anime.
final class FilterFixture {

	static final int ANIME_BASE = 1000;

	static final int THEME_BASE = 5000;

	static final int GROUP_BASE = 9000;

	static final long DURATION_MS = 60000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	record Entry(String type, Integer year, boolean grouped) {
	}

	private FilterFixture() {
	}

	static Entry op(Integer year) {
		return new Entry("OP", year, true);
	}

	static Entry ed(Integer year) {
		return new Entry("ED", year, true);
	}

	static int animeId(int i) {
		return ANIME_BASE + i;
	}

	static int themeId(int i) {
		return THEME_BASE + i;
	}

	static int indexOfTheme(int themeId) {
		return themeId - THEME_BASE;
	}

	static String basename(int i, String type) {
		return "Filter" + i + "-" + type + "1.ogg";
	}

	static Catalog build(Path dir, List<Entry> entries) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int i = 0; i < entries.size(); i++) {
				Entry e = entries.get(i);
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of("Filter Ko " + i));
				titles.put("en", List.of("Filter Anime " + i));
				titles.put("ja", List.of("Filter Ja " + i));
				titles.put("romaji", List.of("Filter Romaji " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", animeId(i));
				a.put("anilistId", 200000 + i);
				a.put("group", e.grouped() ? GROUP_BASE + i : null);
				a.put("titles", titles);
				a.put("reveal", "Filter Reveal " + i);
				if (e.year() != null) {
					a.put("year", e.year());
				}
				anime.add(a);

				if (e.grouped()) {
					LinkedHashSet<String> accepted = new LinkedHashSet<>();
					titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
					groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));
				}

				int size = 10 + i;
				String basename = basename(i, e.type());
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", themeId(i));
				t.put("animeId", animeId(i));
				t.put("type", e.type());
				t.put("slug", e.type() + "1");
				t.put("song", "Filter Song " + i);
				t.put("artists", List.of("Filter Singer " + i));
				t.put("audio", Map.of("basename", basename, "size", size));
				themes.add(t);
				drawSet.add(themeId(i));

				index.put(Integer.toString(themeId(i)),
						Map.of("basename", basename, "durationMs", DURATION_MS, "size", size));
				Files.write(cache.resolve(basename), new byte[size]);
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
			if (catalog.candidates().size() != entries.size()) {
				throw new IllegalStateException("fixture expected " + entries.size() + " candidates, got " + catalog);
			}
			return catalog;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
