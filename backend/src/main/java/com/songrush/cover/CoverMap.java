package com.songrush.cover;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// The anime covers the reveal may name (cycle 093, B-020): animeId -> cover id, and cover id -> its
// file (disk mode) or its S3 key (S3 mode). Immutable once loaded. Loading never throws, unlike the
// audio map: a missing, unreadable or wrong-shape file logs one WARN and gives an empty map, and a
// bad or case-insensitively duplicate entry is skipped. Logs and toString carry counts only, never
// an id, a key or a path of an entry: an id identifies the anime.
public final class CoverMap {

	private static final Logger log = LoggerFactory.getLogger(CoverMap.class);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	public static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private static final Pattern ANIME_ID = Pattern.compile("^-?[0-9]{1,10}$");

	private static final String KEY_PREFIX = "c/";

	// The only cover file names are <id>.jpg and <id>.png (cycle 095, C-022); lower-case suffix only.
	private static final Set<String> FILE_SUFFIXES = Set.of(".jpg", ".png");

	private static final CoverMap EMPTY = new CoverMap("empty", Map.of(), Map.of(), Map.of());

	private final String mode;

	private final Map<Integer, String> idByAnime;

	// Disk maps only.
	private final Map<String, Path> fileById;

	// S3 maps only.
	private final Map<String, String> keyById;

	private CoverMap(String mode, Map<Integer, String> idByAnime, Map<String, Path> fileById,
			Map<String, String> keyById) {
		this.mode = mode;
		this.idByAnime = Map.copyOf(idByAnime);
		this.fileById = Map.copyOf(fileById);
		this.keyById = Map.copyOf(keyById);
	}

	public static CoverMap empty() {
		return EMPTY;
	}

	// Disk mode: coverCacheDir/index.json, {"version": 1, "covers": {"<animeId>": {"id", "file",
	// "size"}}}; "file" is <id>.jpg or <id>.png, and an entry counts only when that file exists in
	// coverCacheDir with that size.
	public static CoverMap loadDisk(Path coverCacheDir) {
		JsonNode root = read(coverCacheDir.resolve("index.json"), "disk cover index");
		if (root == null) {
			return EMPTY;
		}
		JsonNode version = root.get("version");
		JsonNode covers = root.get("covers");
		if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1
				|| covers == null || !covers.isObject()) {
			log.warn("disk cover index is not a version 1 object with a covers object; no covers are served");
			return EMPTY;
		}
		Loader loader = new Loader();
		Map<Integer, String> idByAnime = new HashMap<>();
		Map<String, Path> fileById = new HashMap<>();
		int missing = 0;
		for (Map.Entry<String, JsonNode> field : covers.properties()) {
			Entry entry = loader.entry(field, "file", null, FILE_SUFFIXES);
			if (entry == null) {
				continue;
			}
			// Built from the validated id and one of the fixed suffixes, never from a request.
			Path file = coverCacheDir.resolve(entry.id() + entry.suffix());
			if (!hasSize(file, entry.size())) {
				missing++;
				continue;
			}
			idByAnime.put(entry.animeId(), entry.id());
			fileById.put(entry.id(), file);
		}
		if (missing > 0) {
			log.warn("disk cover index: {} entries skipped because the file is missing or of another size", missing);
		}
		loader.report("disk cover index");
		CoverMap map = new CoverMap("disk", idByAnime, fileById, Map.of());
		log.info("cover map loaded: {}", map);
		return map;
	}

	// S3 mode: the served map, {"<animeId>": {"id", "key", "size"}}; keeps id -> key ("c/<id>").
	// No file is checked.
	public static CoverMap loadS3(Path servedMapFile) {
		JsonNode root = read(servedMapFile, "served cover map");
		if (root == null) {
			return EMPTY;
		}
		Loader loader = new Loader();
		Map<Integer, String> idByAnime = new HashMap<>();
		Map<String, String> keyById = new HashMap<>();
		for (Map.Entry<String, JsonNode> field : root.properties()) {
			Entry entry = loader.entry(field, "key", KEY_PREFIX, Set.of(""));
			if (entry == null) {
				continue;
			}
			idByAnime.put(entry.animeId(), entry.id());
			keyById.put(entry.id(), KEY_PREFIX + entry.id());
		}
		loader.report("served cover map");
		CoverMap map = new CoverMap("s3", idByAnime, Map.of(), keyById);
		log.info("cover map loaded: {}", map);
		return map;
	}

	public Optional<String> idFor(int animeId) {
		return Optional.ofNullable(idByAnime.get(animeId));
	}

	// S3 maps only ("c/<id>"); empty for a disk map. Exact (case-sensitive) lookup.
	public Optional<String> keyFor(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(keyById.get(id));
	}

	// Disk maps only; empty for an S3 map. Exact (case-sensitive) lookup.
	public Optional<Path> fileFor(String id) {
		return id == null ? Optional.empty() : Optional.ofNullable(fileById.get(id));
	}

	public int size() {
		return idByAnime.size();
	}

	// Counts only.
	@Override
	public String toString() {
		return "CoverMap[mode=" + mode + ", covers=" + idByAnime.size() + "]";
	}

	// The file's root object, or null (after one WARN) when it is missing, unreadable, not JSON or
	// not an object. Only the exception type is logged: a parse message can quote an id.
	private static JsonNode read(Path path, String what) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		}
		catch (IOException | RuntimeException e) {
			log.warn("{} cannot be read ({}); no covers are served", what, e.getClass().getSimpleName());
			return null;
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException e) {
			log.warn("{} is not valid JSON ({}); no covers are served", what, e.getClass().getSimpleName());
			return null;
		}
		if (root == null || !root.isObject()) {
			log.warn("{} is not a JSON object; no covers are served", what);
			return null;
		}
		return root;
	}

	private static boolean hasSize(Path file, long size) {
		try {
			return Files.isRegularFile(file) && Files.size(file) == size;
		}
		catch (IOException e) {
			return false;
		}
	}

	// suffix is the matched one of the allowed suffixes ("" for an S3 key).
	private record Entry(int animeId, String id, String suffix, long size) {

		@Override
		public String toString() {
			return "Entry[size=" + size + "]";
		}

	}

	// Checks entries in file order and counts what it skips.
	private static final class Loader {

		private final Set<String> seen = new HashSet<>();

		private int bad;

		private int duplicates;

		// The entry, or null when it is bad or a duplicate. The location field ("file" or "key") must
		// equal prefix + id + one of suffixes, exactly.
		Entry entry(Map.Entry<String, JsonNode> field, String location, String prefix, Set<String> suffixes) {
			String animeKey = field.getKey();
			JsonNode node = field.getValue();
			if (animeKey == null || !ANIME_ID.matcher(animeKey).matches() || node == null || !node.isObject()) {
				bad++;
				return null;
			}
			int animeId;
			try {
				animeId = Integer.parseInt(animeKey);
			}
			catch (NumberFormatException e) {
				bad++;
				return null;
			}
			JsonNode id = node.get("id");
			if (id == null || !id.isString() || !ID.matcher(id.stringValue()).matches()) {
				bad++;
				return null;
			}
			String idText = id.stringValue();
			String stem = (prefix == null ? "" : prefix) + idText;
			JsonNode where = node.get(location);
			String whereText = where == null || !where.isString() ? null : where.stringValue();
			String suffix = whereText == null || !whereText.startsWith(stem) ? null
					: whereText.substring(stem.length());
			if (suffix == null || !suffixes.contains(suffix)) {
				bad++;
				return null;
			}
			JsonNode size = node.get("size");
			if (size == null || !size.isIntegralNumber() || !size.canConvertToLong() || size.longValue() <= 0) {
				bad++;
				return null;
			}
			if (!seen.add(idText.toLowerCase(Locale.ROOT))) {
				duplicates++;
				return null;
			}
			return new Entry(animeId, idText, suffix, size.longValue());
		}

		void report(String what) {
			if (bad > 0) {
				log.warn("{}: {} bad entries skipped", what, bad);
			}
			if (duplicates > 0) {
				log.warn("{}: {} entries skipped because their id repeats an earlier one", what, duplicates);
			}
		}

	}

}
