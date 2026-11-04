package com.songrush.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.songrush.catalog.Anime;
import com.songrush.catalog.Theme;

// The host's limits on one game's draw (docs/cycles/060-draw-filters.md): theme types, an
// inclusive anime year range, and a popularity tier (docs/cycles/063-tier-filter.md). A null field
// is not set. Carries only OP/ED strings, tier names and numbers, so its message fields never
// identify a song.
public record DrawFilter(Set<String> types, Integer yearFrom, Integer yearTo, String tier) {

	public static final DrawFilter NONE = new DrawFilter(null, null, null);

	public static final String EASY = "easy";

	public static final String HARD = "hard";

	// The draw of today's game: the constructor maps it to no tier.
	public static final String NORMAL = "normal";

	// Inclusive draw-set ranks of each tier.
	static final int EASY_LAST = 300;

	static final int HARD_FIRST = 501;

	static final int HARD_LAST = 1500;

	// OP before ED; any other value after them, alphabetically.
	private static final Comparator<String> TYPE_ORDER = Comparator
		.comparingInt((String t) -> "OP".equals(t) ? 0 : "ED".equals(t) ? 1 : 2)
		.thenComparing(Comparator.naturalOrder());

	public DrawFilter {
		if (types != null) {
			types = Set.copyOf(types);
		}
		if (NORMAL.equals(tier)) {
			tier = null;
		}
		if (tier != null && !EASY.equals(tier) && !HARD.equals(tier)) {
			throw new IllegalArgumentException("unknown tier: " + tier);
		}
	}

	public DrawFilter(Set<String> types, Integer yearFrom, Integer yearTo) {
		this(types, yearFrom, yearTo, null);
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

	// True when the 1-based draw-set rank is in the tier: easy 1-300, hard 501-1500; always true
	// without a tier.
	public boolean matchesRank(int rank) {
		if (tier == null) {
			return true;
		}
		if (EASY.equals(tier)) {
			return 1 <= rank && rank <= EASY_LAST;
		}
		return HARD_FIRST <= rank && rank <= HARD_LAST;
	}

	public boolean isEmpty() {
		return types == null && yearFrom == null && yearTo == null && tier == null;
	}

	// The set fields only, in the order types, yearFrom, yearTo, tier; types as a list, OP before ED.
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
		if (tier != null) {
			fields.put("tier", tier);
		}
		return fields;
	}

}
