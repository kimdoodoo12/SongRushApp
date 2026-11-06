package com.songrush.ws;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.songrush.game.Normalize;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Writes catalog.json and audio-cache/index.json, with audio files of the listed sizes, into a data
// folder (copied in spirit from com.songrush.game.GameFixture, which is package-private). Anime i has
// id 1000 + i, theme 5000 + i and group 9000 + i; every anime is drawable, every theme is a cached
// candidate of DURATION_MS. Each file's content is distinct per theme (AudioEndpointTest.audioBytes).
// Every title names its anime's number, so each string is unique to one anime while count <= 10.
// Non-ASCII titles are built from code points (ASCII-only source).
final class WsFixture {

	static final int ANIME_BASE = 1000;

	static final int THEME_BASE = 5000;

	static final int GROUP_BASE = 9000;

	static final long DURATION_MS = 3000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// "sora" in katakana and "haneul" in Hangul.
	private static final String JA = new String(new char[] {(char) 0x30BD, (char) 0x30E9});

	private static final String KO = new String(new char[] {(char) 0xD558, (char) 0xB298});

	private WsFixture() {
	}

	static int themeId(int i) {
		return THEME_BASE + i;
	}

	static int indexOfTheme(int themeId) {
		return themeId - THEME_BASE;
	}

	static String basename(int i) {
		return "SkyGarden" + i + "-OP1.ogg";
	}

	static int size(int i) {
		return 100 + i;
	}

	// Distinctive content per theme: an "OggS" header, then a pattern seeded by the theme id.
	static byte[] audioBytes(int themeId, int size) {
		byte[] bytes = new byte[size];
		byte[] magic = "OggS".getBytes(StandardCharsets.US_ASCII);
		for (int i = 0; i < size; i++) {
			bytes[i] = i < magic.length ? magic[i] : (byte) (i * 7 + themeId * 31);
		}
		return bytes;
	}

	static void write(Path dir, int count) {
		if (count > 10) {
			throw new IllegalArgumentException("titles stay unique only up to 10 anime");
		}
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int i = 0; i < count; i++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of(KO + " " + i));
				titles.put("en", List.of("Sky Garden " + i));
				titles.put("ja", List.of(JA + i));
				titles.put("romaji", List.of("Sora no Niwa " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 100000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", KO + " " + i);
				anime.add(a);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", themeId(i));
				t.put("animeId", ANIME_BASE + i);
				t.put("type", "OP");
				t.put("slug", "OP1");
				t.put("song", "Garden Song " + i);
				t.put("artists", List.of("Singer " + i + " One"));
				t.put("audio", Map.of("basename", basename(i), "size", size(i)));
				themes.add(t);
				drawSet.add(themeId(i));

				index.put(Integer.toString(themeId(i)),
						Map.of("basename", basename(i), "durationMs", DURATION_MS, "size", size(i)));
				Files.write(cache.resolve(basename(i)), audioBytes(themeId(i), size(i)));
			}
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("version", 1);
			root.put("anime", anime);
			root.put("themes", themes);
			root.put("groups", groups);
			root.put("drawSet", drawSet);
			Files.writeString(dir.resolve("catalog.json"), MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
			Files.writeString(cache.resolve("index.json"), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
