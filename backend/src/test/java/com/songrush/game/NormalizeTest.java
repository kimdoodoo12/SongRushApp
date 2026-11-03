package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Cycle 035 (B-004): Normalize.normalize must give the same result as data/songrush_data/titles.py
// normalize. Criterion 1 checks the shared vectors, 2 the catalog's accepted strings (already
// normalized), 2b raw titles against their group's accepted list, 3 null and empty input.
// The source stays ASCII-only: test data comes from the JSON files.
class NormalizeTest {

	private static final Path VECTORS = Path.of("../contracts/normalize-vectors.json");
	private static final Path CATALOG = Path.of("../data/catalog.json");
	private static final int SHOWN = 20;

	private static JsonNode read(Path path) {
		return new ObjectMapper().readTree(path.toFile());
	}

	private static String codePoints(String s) {
		StringBuilder out = new StringBuilder("[");
		s.codePoints().forEach(cp -> {
			if (out.length() > 1) {
				out.append(' ');
			}
			out.append(String.format("U+%04X", cp));
		});
		return out.append(']').toString();
	}

	private static String firstOf(List<String> failures) {
		return String.join("\n", failures.subList(0, Math.min(SHOWN, failures.size())));
	}

	@Test
	void sharedVectorsMatch() {
		JsonNode vectors = read(VECTORS).get("vectors");
		List<String> failures = new ArrayList<>();
		int count = 0;
		for (JsonNode vector : vectors) {
			String input = vector.get("input").asString();
			String expected = vector.get("expected").asString();
			String actual = Normalize.normalize(input);
			if (!expected.equals(actual)) {
				failures.add("vector " + count + ": input " + codePoints(input) + " expected "
						+ codePoints(expected) + " got " + codePoints(actual));
			}
			count++;
		}
		assertThat(count).as("vectors read").isGreaterThanOrEqualTo(79);
		assertThat(failures).as("%d of %d vectors fail; first %d:%n%s", failures.size(), count, SHOWN,
				firstOf(failures)).isEmpty();
	}

	@Test
	void acceptedStringsAreAlreadyNormalized() {
		JsonNode groups = read(CATALOG).get("groups");
		List<String> failures = new ArrayList<>();
		int count = 0;
		for (JsonNode group : groups) {
			int groupId = group.get("id").asInt();
			int index = 0;
			for (JsonNode accepted : group.get("accepted")) {
				String s = accepted.asString();
				String actual = Normalize.normalize(s);
				if (!s.equals(actual)) {
					failures.add("group " + groupId + " accepted[" + index + "]: " + codePoints(s) + " became "
							+ codePoints(actual));
				}
				index++;
				count++;
			}
		}
		assertThat(count).as("accepted strings read").isGreaterThanOrEqualTo(22_000);
		assertThat(failures).as("%d of %d accepted strings change; first %d:%n%s", failures.size(), count,
				SHOWN, firstOf(failures)).isEmpty();
	}

	@Test
	void rawTitlesReachTheirGroup() {
		JsonNode root = read(CATALOG);
		Map<Integer, Set<String>> acceptedByGroup = new HashMap<>();
		for (JsonNode group : root.get("groups")) {
			Set<String> accepted = new HashSet<>();
			for (JsonNode s : group.get("accepted")) {
				accepted.add(s.asString());
			}
			acceptedByGroup.put(group.get("id").asInt(), accepted);
		}
		List<String> failures = new ArrayList<>();
		int count = 0;
		for (JsonNode anime : root.get("anime")) {
			JsonNode group = anime.get("group");
			if (group == null || group.isNull()) {
				continue;
			}
			int animeId = anime.get("id").asInt();
			int groupId = group.asInt();
			Set<String> accepted = acceptedByGroup.get(groupId);
			assertThat(accepted).as("anime %d names group %d, which exists", animeId, groupId).isNotNull();
			for (Map.Entry<String, JsonNode> slot : anime.get("titles").properties()) {
				int index = 0;
				for (JsonNode title : slot.getValue()) {
					String t = title.asString();
					String actual = Normalize.normalize(t);
					if (!accepted.contains(actual)) {
						failures.add("anime " + animeId + " titles." + slot.getKey() + "[" + index + "] "
								+ codePoints(t) + " -> " + codePoints(actual) + " not in group " + groupId);
					}
					index++;
					count++;
				}
			}
		}
		assertThat(count).as("(anime, title) pairs checked").isGreaterThanOrEqualTo(21_000);
		assertThat(failures).as("%d of %d raw titles miss their group; first %d:%n%s", failures.size(), count,
				SHOWN, firstOf(failures)).isEmpty();
	}

	@Test
	void nullThrowsAndEmptyStaysEmpty() {
		assertThatThrownBy(() -> Normalize.normalize(null)).isInstanceOf(NullPointerException.class);
		assertThat(Normalize.normalize("")).isEqualTo("");
	}
}
