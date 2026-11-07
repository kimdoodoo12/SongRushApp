package com.songrush.feedback;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 083 test helpers for the feedback store file (D-097). Shared by the feedback tests.
//
// The store file format these helpers write and read (production must load and write the same):
//   <store-dir>/feedback.json, UTF-8 JSON: {"items":[ <record>, ... ]}
//   <record> = {"id": string, "createdAt": ISO-8601 instant string (Instant.toString form),
//               "category": string, "title": string, "body": string, "lang": string, "page": string,
//               "status": "new"|"sent"|"hidden", "issueNumber": int or null, "issueUrl": string or null,
//               "updatedAt": ISO-8601 instant string}
//   The order of items in the file is not significant; production orders by createdAt.
//
// newStoreDir() makes a fresh folder for a Spring context (a @DynamicPropertySource needs it before
// JUnit's @TempDir is available in a nested class); it is removed when the JVM exits.
// breakDir() replaces the folder with a regular file of the same name, so every later write fails with
// an IOException that is not AtomicMoveNotSupportedException; restoreDir() undoes it.
final class FeedbackFiles {

	static final String FILE = "feedback.json";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private FeedbackFiles() {
	}

	static Path newStoreDir() {
		try {
			Path dir = Files.createTempDirectory("songrush-feedback-test-");
			Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteQuietly(dir)));
			return dir;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// A path inside a fresh temp folder that is a regular file, not a folder.
	static Path newRegularFile() {
		Path parent = newStoreDir();
		try {
			return Files.writeString(parent.resolve("not-a-folder"), "x", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		if (Files.isDirectory(path)) {
			List<Path> all;
			try (Stream<Path> walk = Files.walk(path)) {
				all = walk.sorted(Comparator.reverseOrder()).toList();
			}
			for (Path p : all) {
				Files.delete(p);
			}
		}
		else {
			Files.delete(path);
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			deleteRecursively(path);
		}
		catch (IOException e) {
			// JVM exit cleanup of a temp folder: nothing more can be done.
		}
	}

	static void breakDir(Path dir) throws IOException {
		deleteRecursively(dir);
		Files.writeString(dir, "this is a file, not a folder", StandardCharsets.UTF_8);
	}

	static void restoreDir(Path dir) throws IOException {
		deleteRecursively(dir);
		Files.createDirectories(dir);
	}

	// A record map for seeding a store file.
	static Map<String, Object> record(String id, Instant createdAt, String status) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		map.put("createdAt", createdAt.toString());
		map.put("category", "bug");
		map.put("title", "seed " + id);
		map.put("body", "seed body " + id);
		map.put("lang", "ko");
		map.put("page", "start");
		map.put("status", status);
		boolean sent = "sent".equals(status);
		map.put("issueNumber", sent ? 1 : null);
		map.put("issueUrl", sent ? "https://github.com/kimdoodoo12/SongRushApp/issues/1" : null);
		map.put("updatedAt", createdAt.toString());
		return map;
	}

	// A 12-character URL-safe seed id: "seed" + 8 digits.
	static String seedId(int index) {
		return String.format("seed%08d", index);
	}

	// count records, the i-th created at base + i seconds, with the status chosen by statusOf(i).
	static List<Map<String, Object>> seed(int count, Instant base, java.util.function.IntFunction<String> statusOf) {
		List<Map<String, Object>> records = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			records.add(record(seedId(i), base.plusSeconds(i), statusOf.apply(i)));
		}
		return records;
	}

	static void writeFile(Path dir, List<Map<String, Object>> records) {
		try {
			Files.createDirectories(dir);
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("items", records);
			Files.write(dir.resolve(FILE), MAPPER.writeValueAsBytes(root));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static JsonNode readFile(Path dir) throws IOException {
		return MAPPER.readTree(Files.readAllBytes(dir.resolve(FILE)));
	}

}
