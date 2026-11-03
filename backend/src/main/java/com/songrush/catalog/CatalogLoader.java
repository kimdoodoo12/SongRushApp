package com.songrush.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Reads data/catalog.json and the audio-cache index into a Catalog (cycle 032, design 029, D-056).
// Unknown fields are ignored. Every failure is a CatalogException naming the file and the reason.
public final class CatalogLoader {

	private static final Logger log = LoggerFactory.getLogger(CatalogLoader.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final List<String> TITLE_KEYS = List.of("ko", "en", "ja", "romaji");

	private CatalogLoader() {
	}

	public static Catalog load(Path catalogJson, Path audioIndexJson) {
		JsonNode root = readJson(catalogJson, "catalog");
		Parser parser = new Parser(catalogJson);
		parser.required(root, "version", "catalog");

		Map<Integer, List<String>> accepted = new HashMap<>();
		for (JsonNode group : parser.array(root, "groups")) {
			int id = parser.requiredInt(group, "id", "group");
			parser.unique(accepted.containsKey(id), "group", id);
			accepted.put(id, parser.strings(group, "accepted", "group " + id));
		}

		Map<Integer, Anime> anime = new HashMap<>();
		for (JsonNode node : parser.array(root, "anime")) {
			Anime a = parser.anime(node);
			parser.unique(anime.containsKey(a.id()), "anime", a.id());
			if (a.group() != null && !accepted.containsKey(a.group())) {
				throw parser.fail("anime " + a.id() + " names group " + a.group() + ", which is not a group");
			}
			anime.put(a.id(), a);
		}

		Map<Integer, Theme> themes = new HashMap<>();
		for (JsonNode node : parser.array(root, "themes")) {
			Theme t = parser.theme(node);
			parser.unique(themes.containsKey(t.id()), "theme", t.id());
			if (!anime.containsKey(t.animeId())) {
				throw parser.fail("theme " + t.id() + " names anime " + t.animeId() + ", which is not an anime");
			}
			themes.put(t.id(), t);
		}

		List<Integer> drawSet = new ArrayList<>();
		for (JsonNode node : parser.array(root, "drawSet")) {
			if (!isInt(node)) {
				throw parser.fail("drawSet has a non-integer entry: " + node);
			}
			int id = node.intValue();
			if (!themes.containsKey(id)) {
				throw parser.fail("drawSet names theme " + id + ", which is not a theme");
			}
			drawSet.add(id);
		}

		List<Candidate> candidates = candidates(drawSet, themes, audioIndexJson);
		return new Catalog(anime, themes, accepted, drawSet, candidates);
	}

	// D-056: a draw-set theme with an index entry of the same basename, a positive duration, and a
	// cached file of exactly the entry's size (paddedSize when present). Others are left out.
	private static List<Candidate> candidates(List<Integer> drawSet, Map<Integer, Theme> themes, Path indexJson) {
		if (!Files.exists(indexJson)) {
			log.warn("audio index not found, no draw candidates: {}", indexJson);
			return List.of();
		}
		JsonNode index = readJson(indexJson, "audio index");
		Path cacheDir = indexJson.toAbsolutePath().getParent();
		List<Candidate> candidates = new ArrayList<>();
		for (int themeId : drawSet) {
			Theme theme = themes.get(themeId);
			JsonNode entry = index.get(Integer.toString(themeId));
			if (entry == null || !entry.isObject()) {
				continue;
			}
			JsonNode basename = entry.get("basename");
			JsonNode duration = entry.get("durationMs");
			JsonNode size = entry.has("paddedSize") ? entry.get("paddedSize") : entry.get("size");
			if (basename == null || !basename.isString() || !basename.stringValue().equals(theme.audioBasename())
					|| !isSafeBasename(theme.audioBasename())) {
				continue;
			}
			if (!isLong(duration) || duration.longValue() <= 0 || !isLong(size)) {
				continue;
			}
			if (hasSize(cacheDir.resolve(theme.audioBasename()), size.longValue())) {
				candidates.add(new Candidate(themeId, theme.audioBasename(), duration.longValue()));
			}
		}
		return candidates;
	}

	private static boolean hasSize(Path file, long expected) {
		if (!Files.isRegularFile(file)) {
			return false;
		}
		try {
			return Files.size(file) == expected;
		}
		catch (IOException e) {
			// The file vanished or became unreadable after the check: it is not cached.
			log.warn("cannot read audio file size, leaving it out: {} ({})", file, e.getMessage());
			return false;
		}
	}

	// A plain file name: the index must not point outside the cache folder.
	private static boolean isSafeBasename(String basename) {
		return !basename.isEmpty() && !basename.equals(".") && !basename.equals("..") && !basename.contains("/")
				&& !basename.contains("\\") && !basename.contains(":");
	}

	private static JsonNode readJson(Path path, String what) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		}
		catch (IOException e) {
			throw new CatalogException("cannot read " + what + " " + path + ": " + e, e);
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException e) {
			throw new CatalogException(what + " " + path + " is not valid JSON: " + e.getOriginalMessage(), e);
		}
		if (root == null || !root.isObject()) {
			throw new CatalogException(what + " " + path + " is not a JSON object");
		}
		return root;
	}

	private static boolean isInt(JsonNode node) {
		return node != null && node.isIntegralNumber() && node.canConvertToInt();
	}

	private static boolean isLong(JsonNode node) {
		return node != null && node.isIntegralNumber() && node.canConvertToLong();
	}

	// Field access on one catalog file; failures name that file.
	private static final class Parser {

		private final Path path;

		Parser(Path path) {
			this.path = path;
		}

		CatalogException fail(String reason) {
			return new CatalogException("catalog " + path + ": " + reason);
		}

		JsonNode required(JsonNode node, String field, String where) {
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				throw fail(where + " is missing the required field '" + field + "'");
			}
			return value;
		}

		JsonNode array(JsonNode root, String field) {
			JsonNode value = required(root, field, "catalog");
			if (!value.isArray()) {
				throw fail("'" + field + "' is not an array");
			}
			return value;
		}

		int requiredInt(JsonNode node, String field, String where) {
			if (!node.isObject()) {
				throw fail(where + " is not an object: " + node);
			}
			JsonNode value = required(node, field, where);
			if (!isInt(value)) {
				throw fail(where + " has a non-integer '" + field + "': " + value);
			}
			return value.intValue();
		}

		Integer optionalInt(JsonNode node, String field, String where) {
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				return null;
			}
			if (!isInt(value)) {
				throw fail(where + " has a non-integer '" + field + "': " + value);
			}
			return value.intValue();
		}

		String optionalString(JsonNode node, String field, String where) {
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				return null;
			}
			if (!value.isString()) {
				throw fail(where + " has a non-string '" + field + "': " + value);
			}
			return value.stringValue();
		}

		// A missing or null list is empty; a non-string element is an error.
		List<String> strings(JsonNode node, String field, String where) {
			JsonNode value = node.get(field);
			if (value == null || value.isNull()) {
				return List.of();
			}
			if (!value.isArray()) {
				throw fail(where + " has a non-array '" + field + "'");
			}
			List<String> out = new ArrayList<>();
			for (JsonNode element : value) {
				if (!element.isString()) {
					throw fail(where + " has a non-string entry in '" + field + "': " + element);
				}
				out.add(element.stringValue());
			}
			return out;
		}

		Anime anime(JsonNode node) {
			int id = requiredInt(node, "id", "anime");
			String where = "anime " + id;
			JsonNode titlesNode = node.get("titles");
			if (titlesNode != null && !titlesNode.isNull() && !titlesNode.isObject()) {
				throw fail(where + " has a non-object 'titles'");
			}
			Map<String, List<String>> titles = new LinkedHashMap<>();
			for (String key : TITLE_KEYS) {
				titles.put(key, titlesNode == null || titlesNode.isNull() ? List.of()
						: strings(titlesNode, key, where + " titles"));
			}
			return new Anime(id, optionalInt(node, "anilistId", where), optionalInt(node, "group", where), titles,
					optionalString(node, "reveal", where), optionalInt(node, "year", where));
		}

		Theme theme(JsonNode node) {
			int id = requiredInt(node, "id", "theme");
			String where = "theme " + id;
			int animeId = requiredInt(node, "animeId", where);
			JsonNode audio = required(node, "audio", where);
			if (!audio.isObject()) {
				throw fail(where + " has a non-object 'audio'");
			}
			String basename = optionalString(audio, "basename", where + " audio");
			if (basename == null) {
				throw fail(where + " audio is missing the required field 'basename'");
			}
			return new Theme(id, animeId, optionalString(node, "type", where), optionalString(node, "slug", where),
					optionalString(node, "song", where), strings(node, "artists", where), basename);
		}

		void unique(boolean duplicate, String what, int id) {
			if (duplicate) {
				throw fail("duplicate " + what + " id " + id);
			}
		}

	}

}
