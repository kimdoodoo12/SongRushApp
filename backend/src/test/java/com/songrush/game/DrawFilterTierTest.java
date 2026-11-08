package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

// Cycle 063 (B-012) criterion 1: the tier component of DrawFilter, the three-argument constructor,
// isEmpty and toMessageFields (docs/cycles/063-tier-filter.md, Behaviour 2 and the spec review's
// constructor normalization). Cycle 094 (B-021) criteria 2 and 5: matchesAnimeRank with the nested
// tiers by anime rank, easy 1-100, no tier (normal) 1-300, hard any rank (docs/cycles/094-nested-tiers.md).
class DrawFilterTierTest {

	private static final DrawFilter EASY = new DrawFilter(null, null, null, "easy");

	private static final DrawFilter HARD = new DrawFilter(null, null, null, "hard");

	private static final DrawFilter NO_TIER = new DrawFilter(null, null, null, null);

	// ---- matchesAnimeRank ----

	@Test
	void theTierBoundsAreOneHundredAndThreeHundredAnime() {
		assertThat(DrawFilter.EASY_LAST_ANIME).isEqualTo(100);
		assertThat(DrawFilter.NORMAL_LAST_ANIME).isEqualTo(300);
	}

	@Test
	void easyMatchesAnimeRanksOneToOneHundred() {
		assertThat(EASY.matchesAnimeRank(-1)).isFalse();
		assertThat(EASY.matchesAnimeRank(0)).isFalse();
		assertThat(EASY.matchesAnimeRank(1)).isTrue();
		assertThat(EASY.matchesAnimeRank(50)).isTrue();
		assertThat(EASY.matchesAnimeRank(100)).isTrue();
		assertThat(EASY.matchesAnimeRank(101)).isFalse();
		assertThat(EASY.matchesAnimeRank(300)).isFalse();
		assertThat(EASY.matchesAnimeRank(301)).isFalse();
		assertThat(EASY.matchesAnimeRank(1500)).isFalse();
	}

	@Test
	void noTierAndNormalMatchAnimeRanksOneToThreeHundred() {
		for (DrawFilter filter : List.of(NO_TIER, DrawFilter.NONE, new DrawFilter(null, null, null, "normal"),
				new DrawFilter(Set.of("OP"), 1990, 2000))) {
			assertThat(filter.matchesAnimeRank(-1)).as(filter + " rank -1").isFalse();
			assertThat(filter.matchesAnimeRank(0)).as(filter + " rank 0").isFalse();
			for (int rank : new int[] {1, 2, 100, 101, 299, 300}) {
				assertThat(filter.matchesAnimeRank(rank)).as(filter + " rank " + rank).isTrue();
			}
			for (int rank : new int[] {301, 500, 1500, 100000}) {
				assertThat(filter.matchesAnimeRank(rank)).as(filter + " rank " + rank).isFalse();
			}
		}
	}

	@Test
	void hardMatchesEveryAnimeRankFromOne() {
		assertThat(HARD.matchesAnimeRank(-1)).isFalse();
		assertThat(HARD.matchesAnimeRank(0)).isFalse();
		for (int rank : new int[] {1, 2, 100, 101, 300, 301, 353, 500, 1500, 3000, 100000}) {
			assertThat(HARD.matchesAnimeRank(rank)).as("hard rank " + rank).isTrue();
		}
	}

	@Test
	void theTiersNest() {
		for (int rank = 1; rank <= 400; rank++) {
			if (EASY.matchesAnimeRank(rank)) {
				assertThat(NO_TIER.matchesAnimeRank(rank)).as("easy rank " + rank + " is in normal").isTrue();
			}
			if (NO_TIER.matchesAnimeRank(rank)) {
				assertThat(HARD.matchesAnimeRank(rank)).as("normal rank " + rank + " is in hard").isTrue();
			}
		}
	}

	@Test
	void otherFieldsDoNotChangeTheAnimeRankCheck() {
		DrawFilter easyEd = new DrawFilter(Set.of("ED"), 1990, 2000, "easy");
		assertThat(easyEd.matchesAnimeRank(100)).isTrue();
		assertThat(easyEd.matchesAnimeRank(101)).isFalse();
		DrawFilter normalOp = new DrawFilter(Set.of("OP"), 1990, null, "normal");
		assertThat(normalOp.matchesAnimeRank(300)).isTrue();
		assertThat(normalOp.matchesAnimeRank(301)).isFalse();
		DrawFilter hardOp = new DrawFilter(Set.of("OP"), null, 2010, "hard");
		assertThat(hardOp.matchesAnimeRank(0)).isFalse();
		assertThat(hardOp.matchesAnimeRank(1)).isTrue();
		assertThat(hardOp.matchesAnimeRank(301)).isTrue();
		assertThat(hardOp.matchesAnimeRank(1501)).isTrue();
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
