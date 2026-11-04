package com.songrush.catalog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Immutable in-memory catalog lookups and the draw candidates; built by CatalogLoader.
// Holds answers: never serialize it into a response (cycle 032, answer safety).
public final class Catalog {

	private final Map<Integer, Anime> anime;

	private final Map<Integer, Theme> themes;

	private final Map<Integer, List<String>> accepted;

	private final List<Integer> drawSet;

	private final List<Candidate> candidates;

	// Draw-set theme id to its 1-based position.
	private final Map<Integer, Integer> ranks;

	Catalog(Map<Integer, Anime> anime, Map<Integer, Theme> themes, Map<Integer, List<String>> accepted,
			List<Integer> drawSet, List<Candidate> candidates) {
		this.anime = Map.copyOf(anime);
		this.themes = Map.copyOf(themes);
		Map<Integer, List<String>> copy = new HashMap<>();
		accepted.forEach((group, strings) -> copy.put(group, List.copyOf(strings)));
		this.accepted = Map.copyOf(copy);
		this.drawSet = List.copyOf(drawSet);
		this.candidates = List.copyOf(candidates);
		Map<Integer, Integer> byTheme = new HashMap<>();
		for (int i = 0; i < this.drawSet.size(); i++) {
			byTheme.putIfAbsent(this.drawSet.get(i), i + 1);
		}
		this.ranks = Map.copyOf(byTheme);
	}

	public Optional<Anime> anime(int id) {
		return Optional.ofNullable(anime.get(id));
	}

	public Optional<Theme> theme(int id) {
		return Optional.ofNullable(themes.get(id));
	}

	// The group's accepted answer strings; empty when the group is unknown.
	public List<String> accepted(int groupId) {
		return accepted.getOrDefault(groupId, List.of());
	}

	// Draw-set theme ids in the file's order.
	public List<Integer> drawSet() {
		return drawSet;
	}

	// The theme's 1-based position in drawSet (its popularity rank, cached or not); 0 when the theme
	// is not in the draw set.
	public int rank(int themeId) {
		return ranks.getOrDefault(themeId, 0);
	}

	// Cached draw-set themes in draw-set order.
	public List<Candidate> candidates() {
		return candidates;
	}

	// The draw candidate for the theme; empty when the theme is not a candidate.
	public Optional<Candidate> candidate(int themeId) {
		return candidates.stream().filter(c -> c.themeId() == themeId).findFirst();
	}

	public int animeCount() {
		return anime.size();
	}

	public int themeCount() {
		return themes.size();
	}

	// Counts only: the contents are answers.
	@Override
	public String toString() {
		return "Catalog[" + anime.size() + " anime, " + themes.size() + " themes, " + drawSet.size()
				+ " draw set, " + candidates.size() + " candidates]";
	}

}
