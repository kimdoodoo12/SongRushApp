package com.songrush.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.songrush.catalog.Anime;
import com.songrush.catalog.Theme;

// The host's limits on one game's draw (docs/cycles/060-draw-filters.md): theme types and an
// inclusive anime year range. A null field is not set. Carries only OP/ED strings and numbers, so
// its message fields never identify a song.
public record DrawFilter(Set<String> types, Integer yearFrom, Integer yearTo) {

	public static final DrawFilter NONE = new DrawFilter(null, null, null);

	// OP before ED; any other value after them, alphabetically.
	private static final Comparator<String> TYPE_ORDER = Comparator
		.comparingInt((String t) -> "OP".equals(t) ? 0 : "ED".equals(t) ? 1 : 2)
		.thenComparing(Comparator.naturalOrder());

	public DrawFilter {
		if (types != null) {
			types = Set.copyOf(types);
		}
	}

	// True when every set field passes; a set year bound never matches an anime without a year.
	public boolean matches(Theme theme, Anime anime) {
		if (types != null && !types.contains(theme.type())) {
			return false;
		}
		if (yearFrom == null && yearTo == null) {
			return true;
		}
		Integer year = anime.year();
		if (year == null) {
			return false;
		}
		return (yearFrom == null || yearFrom <= year) && (yearTo == null || year <= yearTo);
	}

	public boolean isEmpty() {
		return types == null && yearFrom == null && yearTo == null;
	}

	// The set fields only, in the order types, yearFrom, yearTo; types as a list, OP before ED.
	public Map<String, Object> toMessageFields() {
		Map<String, Object> fields = new LinkedHashMap<>();
		if (types != null) {
			List<String> sorted = new ArrayList<>(types);
			sorted.sort(TYPE_ORDER);
			fields.put("types", sorted);
		}
		if (yearFrom != null) {
			fields.put("yearFrom", yearFrom);
		}
		if (yearTo != null) {
			fields.put("yearTo", yearTo);
		}
		return fields;
	}

}
