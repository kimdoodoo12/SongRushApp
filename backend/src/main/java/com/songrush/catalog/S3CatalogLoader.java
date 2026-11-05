package com.songrush.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

// S3 mode (cycle 074, design 073): the catalog plus audio-cache/s3-keys.json, the served map
// {basename: {"key": "a/...", "size": n}} written by the data tool. A draw-set theme is a candidate
// when its index entry is valid and the served map holds its basename with the index size; no local
// audio file is needed. A missing or invalid map or index, or zero candidates, is a CatalogException.
public final class S3CatalogLoader {

	private static final String KEY_PREFIX = "a/";

	private S3CatalogLoader() {
	}

	public static S3Catalog load(Path catalogJson, Path indexJson, Path servedMapJson) {
		Map<String, Served> served = readServedMap(servedMapJson);
		CatalogLoader.Parsed parsed = CatalogLoader.parse(catalogJson);
		if (!Files.exists(indexJson)) {
			throw new CatalogException("audio index " + indexJson + " not found; S3 mode needs it");
		}
		JsonNode index = CatalogLoader.readJson(indexJson, "audio index");
		List<Candidate> candidates = CatalogLoader.candidates(parsed, index, false, (basename, size) -> {
			Served entry = served.get(basename);
			if (entry == null) {
				return CatalogLoader.Drop.NOT_STORED;
			}
			return entry.size() == size ? null : CatalogLoader.Drop.SIZE_MISMATCH;
		}, "S3");
		if (candidates.isEmpty()) {
			throw new CatalogException("no draw candidate: no draw-set theme of audio index " + indexJson
					+ " is in served map " + servedMapJson + " with the index size");
		}
		Map<String, String> keyByBasename = new HashMap<>();
		for (Candidate candidate : candidates) {
			keyByBasename.put(candidate.audioBasename(), served.get(candidate.audioBasename()).key());
		}
		return new S3Catalog(parsed.withCandidates(candidates), keyByBasename);
	}

	// Every entry is checked, not only the candidates': an invalid map means a broken upload run.
	// Messages name the map path and never a key.
	private static Map<String, Served> readServedMap(Path path) {
		if (!Files.exists(path)) {
			throw new CatalogException("served map " + path + " not found; S3 mode needs it");
		}
		JsonNode root = CatalogLoader.readJson(path, "served map");
		Map<String, Served> served = new HashMap<>();
		for (Map.Entry<String, JsonNode> field : root.properties()) {
			JsonNode entry = field.getValue();
			if (entry == null || !entry.isObject()) {
				throw new CatalogException("served map " + path + " has a non-object entry");
			}
			JsonNode key = entry.get("key");
			if (key == null || !key.isString() || !key.stringValue().startsWith(KEY_PREFIX)
					|| key.stringValue().length() == KEY_PREFIX.length()) {
				throw new CatalogException("served map " + path + " has an entry whose key does not start with '"
						+ KEY_PREFIX + "'");
			}
			JsonNode size = entry.get("size");
			if (!CatalogLoader.isLong(size) || size.longValue() <= 0) {
				throw new CatalogException("served map " + path + " has an entry whose size is not a positive integer");
			}
			served.put(field.getKey(), new Served(key.stringValue(), size.longValue()));
		}
		return served;
	}

	private record Served(String key, long size) {

		@Override
		public String toString() {
			return "Served[size=" + size + "]";
		}

	}

}
