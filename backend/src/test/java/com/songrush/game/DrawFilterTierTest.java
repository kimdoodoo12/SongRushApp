package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

// Cycle 063 (B-012) criterion 1: the tier component of DrawFilter, matchesRank, the three-argument
// constructor, isEmpty and toMessageFields (docs/cycles/063-tier-filter.md, Behaviour 2 and the
// spec review's constructor normalization).
class DrawFilterTierTest {

	private static final DrawFilter EASY = new DrawFilter(null, null, null, "easy");

	private static final DrawFilter HARD = new DrawFilter(null, null, null, "hard");

	private static final DrawFilter NO_TIER = new DrawFilter(null, null, null, null);

	// ---- matchesRank ----

	@Test
	void easyMatchesRanksOneToThreeHundred() {
		assertThat(EASY.matchesRank(0)).isFalse();
		assertThat(EASY.matchesRank(1)).isTrue();
		assertThat(EASY.matchesRank(150)).isTrue();
		assertThat(EASY.matchesRank(300)).isTrue();
		assertThat(EASY.matchesRank(301)).isFalse();
		assertThat(EASY.matchesRank(500)).isFalse();
		assertThat(EASY.matchesRank(501)).isFalse();
		assertThat(EASY.matchesRank(1500)).isFalse();
		assertThat(EASY.matchesRank(-1)).isFalse();
	}

	@Test
	void hardMatchesRanksFiveHundredOneToFifteenHundred() {
		assertThat(HARD.matchesRank(0)).isFalse();
		assertThat(HARD.matchesRank(1)).isFalse();
		assertThat(HARD.matchesRank(300)).isFalse();
		assertThat(HARD.matchesRank(301)).isFalse();
		assertThat(HARD.matchesRank(500)).isFalse();
		assertThat(HARD.matchesRank(501)).isTrue();
		assertThat(HARD.matchesRank(1000)).isTrue();
		assertThat(HARD.matchesRank(1500)).isTrue();
		assertThat(HARD.matchesRank(1501)).isFalse();
		assertThat(HARD.matchesRank(-1)).isFalse();
	}

	@Test
	void noTierMatchesEveryRank() {
		for (DrawFilter filter : List.of(NO_TIER, DrawFilter.NONE, new DrawFilter(Set.of("OP"), 1990, 2000))) {
			for (int rank : new int[] {1, 2, 300, 301, 500, 501, 1500, 1501, 100000}) {
				assertThat(filter.matchesRank(rank)).as(filter + " rank " + rank).isTrue();
			}
		}
	}

	@Test
	void otherFieldsDoNotChangeTheRankCheck() {
		DrawFilter easyEd = new DrawFilter(Set.of("ED"), 1990, 2000, "easy");
		assertThat(easyEd.matchesRank(300)).isTrue();
		assertThat(easyEd.matchesRank(301)).isFalse();
		DrawFilter hardOp = new DrawFilter(Set.of("OP"), null, 2010, "hard");
		assertThat(hardOp.matchesRank(500)).isFalse();
		assertThat(hardOp.matchesRank(501)).isTrue();
		assertThat(hardOp.matchesRank(1501)).isFalse();
	}

	// ---- construction ----

	@Test
	void theThreeArgumentConstructorEqualsTheFourArgumentOneWithANullTier() {
		assertThat(new DrawFilter(null, null, null)).isEqualTo(new DrawFilter(null, null, null, null));
		assertThat(new DrawFilter(Set.of("OP"), 1990, 2005)).isEqualTo(new DrawFilter(Set.of("OP"), 1990, 2005, null));
		assertThat(new DrawFilter(Set.of("OP"), 1990, 2005).hashCode())
				.isEqualTo(new DrawFilter(Set.of("OP"), 1990, 2005, null).hashCode());
		assertThat(new DrawFilter(Set.of("ED"), null, 2000).tier()).isNull();
	}

	@Test
	void theTierIsKeptAndPartOfEquality() {
		assertThat(EASY.tier()).isEqualTo("easy");
		assertThat(HARD.tier()).isEqualTo("hard");
		assertThat(EASY).isNotEqualTo(HARD);
		assertThat(EASY).isNotEqualTo(NO_TIER);
		assertThat(new DrawFilter(Set.of("OP"), 1990, 2005, "hard"))
				.isNotEqualTo(new DrawFilter(Set.of("OP"), 1990, 2005));
	}

	@Test
	void normalIsNormalizedToNull() {
		DrawFilter normal = new DrawFilter(null, null, null, "normal");
		assertThat(normal.tier()).isNull();
		assertThat(normal).isEqualTo(DrawFilter.NONE);
		assertThat(normal.isEmpty()).isTrue();
		assertThat(new DrawFilter(Set.of("OP"), 1990, null, "normal")).isEqualTo(new DrawFilter(Set.of("OP"), 1990, null));
	}

	@Test
	void anyOtherTierIsRejected() {
		for (String bad : new String[] {"medium", "", "Easy", "HARD", " easy", "normal ", "0"}) {
			assertThatThrownBy(() -> new DrawFilter(null, null, null, bad)).as("tier '" + bad + "'")
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	// ---- NONE and isEmpty ----

	@Test
	void noneIsTheEmptyFilter() {
		assertThat(DrawFilter.NONE.tier()).isNull();
		assertThat(DrawFilter.NONE).isEqualTo(NO_TIER);
		assertThat(DrawFilter.NONE).isEqualTo(new DrawFilter(null, null, null));
		assertThat(DrawFilter.NONE.isEmpty()).isTrue();
		assertThat(NO_TIER.isEmpty()).isTrue();
	}

	@Test
	void isEmptyIsFalseWhenOnlyTheTierIsSet() {
		assertThat(EASY.isEmpty()).isFalse();
		assertThat(HARD.isEmpty()).isFalse();
		assertThat(new DrawFilter(Set.of("OP"), null, null, "easy").isEmpty()).isFalse();
	}

	// ---- toMessageFields ----

	private static List<String> keys(Map<String, ?> fields) {
		return new ArrayList<>(fields.keySet());
	}

	@Test
	void tierAloneIsTheOnlyField() {
		Map<String, ?> easy = EASY.toMessageFields();
		assertThat(keys(easy)).containsExactly("tier");
		assertThat(easy.get("tier")).isEqualTo("easy");
		Map<String, ?> hard = HARD.toMessageFields();
		assertThat(keys(hard)).containsExactly("tier");
		assertThat(hard.get("tier")).isEqualTo("hard");
	}

	@Test
	void tierComesLastAfterTheEarlierFields() {
		Map<String, ?> all = new DrawFilter(Set.of("ED", "OP"), 1990, 2005, "hard").toMessageFields();
		assertThat(keys(all)).containsExactly("types", "yearFrom", "yearTo", "tier");
		assertThat(all.get("types")).isEqualTo(List.of("OP", "ED"));
		assertThat(all.get("yearFrom")).isEqualTo(1990);
		assertThat(all.get("yearTo")).isEqualTo(2005);
		assertThat(all.get("tier")).isEqualTo("hard");

		assertThat(keys(new DrawFilter(Set.of("ED"), null, null, "easy").toMessageFields()))
				.containsExactly("types", "tier");
		assertThat(keys(new DrawFilter(null, 1995, null, "easy").toMessageFields()))
				.containsExactly("yearFrom", "tier");
		assertThat(keys(new DrawFilter(null, null, 2010, "hard").toMessageFields()))
				.containsExactly("yearTo", "tier");
	}

	@Test
	void noTierKeyWithoutATier() {
		assertThat(NO_TIER.toMessageFields()).isEmpty();
		assertThat(new DrawFilter(null, null, null, "normal").toMessageFields()).isEmpty();
		assertThat(keys(new DrawFilter(Set.of("OP"), 1990, 2005, null).toMessageFields()))
				.containsExactly("types", "yearFrom", "yearTo");
	}

}
