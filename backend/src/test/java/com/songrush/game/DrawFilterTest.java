package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;

// Cycle 060 (B-011) criterion 1: DrawFilter.matches, isEmpty and toMessageFields
// (docs/cycles/060-draw-filters.md). The themes and anime come from a FilterFixture catalog loaded
// by CatalogLoader, so the test does not depend on the Anime constructor's parameter order.
class DrawFilterTest {

	// Index: type and year.
	private static final List<FilterFixture.Entry> ENTRIES = List.of(
			FilterFixture.op(2000), // 0
			FilterFixture.ed(2000), // 1
			FilterFixture.op(null), // 2
			FilterFixture.ed(1999), // 3
			FilterFixture.op(2001), // 4
			FilterFixture.ed(null)); // 5

	@TempDir
	Path dir;

	private Catalog catalog;

	@BeforeEach
	void load() {
		catalog = FilterFixture.build(dir, ENTRIES);
	}

	private Theme theme(int i) {
		return catalog.theme(FilterFixture.themeId(i)).orElseThrow();
	}

	private Anime anime(int i) {
		return catalog.anime(FilterFixture.animeId(i)).orElseThrow();
	}

	// The fixture indexes the filter matches, in index order.
	private List<Integer> matching(DrawFilter filter) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < ENTRIES.size(); i++) {
			if (filter.matches(theme(i), anime(i))) {
				out.add(i);
			}
		}
		return out;
	}

	@Test
	void fixtureHasTheExpectedTypesAndYears() {
		assertThat(theme(0).type()).isEqualTo("OP");
		assertThat(theme(1).type()).isEqualTo("ED");
		assertThat(anime(0).year()).isEqualTo(2000);
		assertThat(anime(3).year()).isEqualTo(1999);
		assertThat(anime(2).year()).isNull();
	}

	// ---- NONE ----

	@Test
	void noneHasAllFieldsNullAndMatchesEverything() {
		assertThat(DrawFilter.NONE.types()).isNull();
		assertThat(DrawFilter.NONE.yearFrom()).isNull();
		assertThat(DrawFilter.NONE.yearTo()).isNull();
		assertThat(DrawFilter.NONE).isEqualTo(new DrawFilter(null, null, null));
		assertThat(matching(DrawFilter.NONE)).containsExactly(0, 1, 2, 3, 4, 5);
	}

	// ---- matches, one field ----

	@Test
	void typesAloneMatchesTheThemeType() {
		assertThat(matching(new DrawFilter(Set.of("OP"), null, null))).containsExactly(0, 2, 4);
		assertThat(matching(new DrawFilter(Set.of("ED"), null, null))).containsExactly(1, 3, 5);
		assertThat(matching(new DrawFilter(Set.of("OP", "ED"), null, null))).containsExactly(0, 1, 2, 3, 4, 5);
	}

	@Test
	void yearFromAloneIsInclusiveAndExcludesUnknownYears() {
		assertThat(matching(new DrawFilter(null, 2000, null))).containsExactly(0, 1, 4);
		assertThat(matching(new DrawFilter(null, 2001, null))).containsExactly(4);
		assertThat(matching(new DrawFilter(null, 2002, null))).isEmpty();
		assertThat(matching(new DrawFilter(null, 1999, null))).containsExactly(0, 1, 3, 4);
	}

	@Test
	void yearToAloneIsInclusiveAndExcludesUnknownYears() {
		assertThat(matching(new DrawFilter(null, null, 2000))).containsExactly(0, 1, 3);
		assertThat(matching(new DrawFilter(null, null, 1999))).containsExactly(3);
		assertThat(matching(new DrawFilter(null, null, 1998))).isEmpty();
		assertThat(matching(new DrawFilter(null, null, 2001))).containsExactly(0, 1, 3, 4);
	}

	// ---- matches, combined ----

	@Test
	void yearRangeIsInclusiveOnBothEnds() {
		assertThat(matching(new DrawFilter(null, 2000, 2000))).containsExactly(0, 1);
		assertThat(matching(new DrawFilter(null, 1999, 2001))).containsExactly(0, 1, 3, 4);
		assertThat(matching(new DrawFilter(null, 1999, 1999))).containsExactly(3);
		assertThat(matching(new DrawFilter(null, 2001, 2001))).containsExactly(4);
	}

	@Test
	void allFieldsCombine() {
		assertThat(matching(new DrawFilter(Set.of("OP"), 2000, 2001))).containsExactly(0, 4);
		assertThat(matching(new DrawFilter(Set.of("ED"), 1999, 2000))).containsExactly(1, 3);
		assertThat(matching(new DrawFilter(Set.of("ED"), 2001, 2001))).isEmpty();
		assertThat(matching(new DrawFilter(Set.of("OP"), null, 2000))).containsExactly(0);
		assertThat(matching(new DrawFilter(Set.of("ED"), 2000, null))).containsExactly(1);
	}

	@Test
	void unknownYearMatchesOnlyWithoutAYearBound() {
		Theme op = theme(2);
		Anime noYear = anime(2);
		assertThat(noYear.year()).isNull();
		assertThat(new DrawFilter(Set.of("OP"), null, null).matches(op, noYear)).isTrue();
		assertThat(new DrawFilter(null, 1950, null).matches(op, noYear)).isFalse();
		assertThat(new DrawFilter(null, null, 2100).matches(op, noYear)).isFalse();
		assertThat(new DrawFilter(null, 1950, 2100).matches(op, noYear)).isFalse();
		assertThat(new DrawFilter(Set.of("OP"), 1950, 2100).matches(op, noYear)).isFalse();
	}

	// ---- construction ----

	@Test
	void typesAreCopied() {
		Set<String> types = new HashSet<>(Set.of("OP"));
		DrawFilter filter = new DrawFilter(types, null, null);
		types.add("ED");
		assertThat(filter.types()).containsExactly("OP");
		assertThat(matching(filter)).containsExactly(0, 2, 4);
	}

	// ---- isEmpty ----

	@Test
	void isEmptyOnlyWhenEveryFieldIsNull() {
		assertThat(DrawFilter.NONE.isEmpty()).isTrue();
		assertThat(new DrawFilter(null, null, null).isEmpty()).isTrue();
		assertThat(new DrawFilter(Set.of("OP"), null, null).isEmpty()).isFalse();
		assertThat(new DrawFilter(null, 2000, null).isEmpty()).isFalse();
		assertThat(new DrawFilter(null, null, 2000).isEmpty()).isFalse();
		assertThat(new DrawFilter(Set.of("OP", "ED"), 1990, 2000).isEmpty()).isFalse();
	}

	// ---- toMessageFields ----

	private static List<String> keys(Map<String, ?> fields) {
		return new ArrayList<>(fields.keySet());
	}

	@Test
	void noFieldsForNone() {
		assertThat(DrawFilter.NONE.toMessageFields()).isEmpty();
	}

	@Test
	void onlyTheSetFieldsComeOut() {
		Map<String, ?> types = new DrawFilter(Set.of("ED"), null, null).toMessageFields();
		assertThat(keys(types)).containsExactly("types");
		assertThat(types.get("types")).isEqualTo(List.of("ED"));

		Map<String, ?> from = new DrawFilter(null, 1995, null).toMessageFields();
		assertThat(keys(from)).containsExactly("yearFrom");
		assertThat(from.get("yearFrom")).isEqualTo(1995);

		Map<String, ?> to = new DrawFilter(null, null, 2010).toMessageFields();
		assertThat(keys(to)).containsExactly("yearTo");
		assertThat(to.get("yearTo")).isEqualTo(2010);

		Map<String, ?> range = new DrawFilter(null, 1995, 2010).toMessageFields();
		assertThat(keys(range)).containsExactly("yearFrom", "yearTo");
	}

	@Test
	void allFieldsInOrderWithTypesSortedOpBeforeEd() {
		for (Set<String> types : List.of(Set.of("ED", "OP"), Set.of("OP", "ED"))) {
			Map<String, ?> fields = new DrawFilter(types, 1990, 2005).toMessageFields();
			assertThat(keys(fields)).containsExactly("types", "yearFrom", "yearTo");
			assertThat(fields.get("types")).isEqualTo(List.of("OP", "ED"));
			assertThat(fields.get("yearFrom")).isEqualTo(1990);
			assertThat(fields.get("yearTo")).isEqualTo(2005);
		}
		Map<String, ?> typesOnly = new DrawFilter(new LinkedHashSet<>(List.of("ED", "OP")), null, null)
				.toMessageFields();
		assertThat(typesOnly.get("types")).isEqualTo(List.of("OP", "ED"));
	}

}
