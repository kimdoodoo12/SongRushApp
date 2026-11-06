package com.songrush.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One catalog anime. anilistId, group, reveal and year may be null. titles has the keys ko, en, ja, romaji.
// Holds answers: never serialize it into a response (cycle 032, answer safety).
public record Anime(int id, Integer anilistId, Integer group, Map<String, List<String>> titles, String reveal,
		Integer year) {

	public Anime {
		Map<String, List<String>> copy = new LinkedHashMap<>();
		titles.forEach((key, values) -> copy.put(key, List.copyOf(values)));
		titles = Map.copyOf(copy);
	}

	// Ids only: the titles and reveal are answers (cycle 033).
	@Override
	public String toString() {
		return "Anime[id=" + id + "]";
	}

}
