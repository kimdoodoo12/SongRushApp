package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.ChoiceFixture.Spec;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 100 (D-111) at Room level: which songs a choice room draws and which choices each song gets
// (docs/cycles/100-choice-answers.md items 2 and 3, amendments A1, A2, A5 and A9's shuffle test).
// Games run on the cycle 036 fakes with a seeded Random; every song is readied and skipped. A
// choice's anime is found from its coverUrl, the song's anime from the token in prepare.audioUrl.
class RoomChoiceDrawTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final DrawFilter EASY = new DrawFilter(null, null, null, DrawFilter.EASY);

	private static final DrawFilter HARD = new DrawFilter(null, null, null, DrawFilter.HARD);

	@TempDir
	Path dir;

	// ---- harness ----

	// One song as the test sees it: the song's anime and the anime of each choice, by position.
	private record Drawn(int anime, List<Integer> choiceAnime) {

		int correctPosition() {
			return choiceAnime.indexOf(anime);
		}

	}

	// One seeded room with one player.
	private static final class Game {

		final MutableClock clock = new MutableClock();

		final ManualScheduler scheduler = new ManualScheduler(clock);

		final RecordingOutbox outbox = new RecordingOutbox();

		final AudioTokens tokens = new AudioTokens(clock);

		final Built built;

		final Room room;

		Game(Built built, long seed) {
			this.built = built;
			room = new Room(built.catalog(), tokens, scheduler, clock, new Random(seed), outbox, List.of(MINA),
					built.covers());
		}

		@SuppressWarnings("unchecked")
		Drawn current() {
			Map<String, Object> prepare = outbox.last("prepare").message();
			String url = (String) prepare.get("audioUrl");
			int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
			List<Integer> choices = new ArrayList<>();
			for (Object choice : (List<Object>) prepare.get("choices")) {
				String coverUrl = (String) ((Map<String, Object>) choice).get("coverUrl");
				int a = built.animeIndexOfCoverUrl(coverUrl);
				assertThat(a).as("choice " + coverUrl + " is a fixture cover").isGreaterThanOrEqualTo(0);
				choices.add(a);
			}
			return new Drawn(built.animeIndexOfTheme(themeId), choices);
		}

		// Starts a choice game and plays it to gameOver; checks each reveal's correctChoice.
		List<Drawn> play(int songs, DrawFilter filter) {
			room.start(songs, filter, AnswerMode.CHOICE);
			assertThat(room.phase()).as("started").isEqualTo(Room.Phase.PREPARE);
			List<Drawn> drawn = new ArrayList<>();
			for (int k = 0; k < songs; k++) {
				Drawn d = current();
				drawn.add(d);
				room.ready(MINA.id(), k);
				room.skip(MINA.id(), k);
				Map<String, Object> reveal = outbox.last("reveal").message();
				assertThat(reveal.get("correctChoice")).as("song " + k).isEqualTo(d.correctPosition());
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			return drawn;
		}

	}

	// Checks item 3 and A1 for one game: the correct anime once, distractors of distinct groups, none of
	// the correct group or of an earlier song's group, all covered, all in the pool.
	private static void assertChoiceRules(Built built, List<Drawn> game, Set<Integer> pool, String what) {
		Set<Integer> answered = new HashSet<>();
		for (int k = 0; k < game.size(); k++) {
			Drawn d = game.get(k);
			String as = what + " song " + k + " " + d;
			assertThat(d.choiceAnime().stream().filter(a -> a == d.anime()).count()).as(as).isEqualTo(1);
			assertThat(pool).as(as + ": the song is in the pool").contains(d.anime());
			int correctGroup = built.groupOf(d.anime());
			Set<Integer> groups = new HashSet<>();
			for (int a : d.choiceAnime()) {
				assertThat(built.specs().get(a).cover()).as(as).isTrue();
				assertThat(pool).as(as + ": choice anime " + a + " in the pool").contains(a);
				assertThat(groups.add(built.groupOf(a))).as(as + ": distinct groups").isTrue();
				if (a != d.anime()) {
					assertThat(answered).as(as + ": distractor " + a + " of an earlier answer's group")
							.doesNotContain(built.groupOf(a));
				}
			}
			answered.add(correctGroup);
		}
	}

	// ---- item 3 and A1, A5: the choice rules ----

	// Anime 0-59: group a / 2 (two anime per group), OP of year 1990 + a % 20 with two themes, and
	// every fifth anime (a % 5 == 4) without a cover; anime 60-119: own group, ED of year 2000, all
	// covered, ranks 61-120 (so 101-120 are outside easy).
	private static List<Spec> richSpecs() {
		List<Spec> specs = new ArrayList<>();
		for (int a = 0; a < 60; a++) {
			specs.add(new Spec(a / 2, 2, "OP", 1990 + a % 20, a % 5 != 4));
		}
		for (int a = 60; a < 120; a++) {
			specs.add(new Spec(1000 + a, 1, "ED", 2000, true));
		}
		return specs;
	}

	private static Set<Integer> poolOf(Built built, IntPredicate filter) {
		Set<Integer> pool = new HashSet<>();
		for (int a = 0; a < built.specs().size(); a++) {
			if (built.specs().get(a).cover() && filter.test(a)) {
				pool.add(a);
			}
		}
		return pool;
	}

	@Test
	void hardChoicesFollowTheRulesOverManySeeds() {
		Built built = ChoiceFixture.build(dir, richSpecs());
		Set<Integer> pool = poolOf(built, a -> true);
		for (long seed = 0; seed < 150; seed++) {
			List<Drawn> game = new Game(built, seed).play(10, HARD);
			game.forEach(d -> assertThat(d.choiceAnime()).hasSize(8));
			assertChoiceRules(built, game, pool, "seed " + seed);
		}
	}

	@Test
	void easyChoicesStayInsideTheEasyPool() {
		Built built = ChoiceFixture.build(dir, richSpecs());
		Set<Integer> pool = poolOf(built, a -> a < DrawFilter.EASY_LAST_ANIME);
		for (long seed = 0; seed < 150; seed++) {
			List<Drawn> game = new Game(built, seed).play(10, EASY);
			game.forEach(d -> assertThat(d.choiceAnime()).hasSize(4));
			assertChoiceRules(built, game, pool, "seed " + seed);
		}
	}

	@Test
	void choicesStayInsideTheTypeAndYearFilter() {
		Built built = ChoiceFixture.build(dir, richSpecs());
		// OP of 1990-1999: anime 0-9, 20-29, 40-49 (covered ones), 15 groups.
		DrawFilter filter = new DrawFilter(Set.of("OP"), 1990, 1999, DrawFilter.HARD);
		Set<Integer> pool = poolOf(built, a -> a < 60 && a % 20 < 10);
		for (long seed = 0; seed < 150; seed++) {
			List<Drawn> game = new Game(built, seed).play(5, filter);
			game.forEach(d -> assertThat(d.choiceAnime()).hasSize(8));
			assertChoiceRules(built, game, pool, "seed " + seed);
		}
	}

	// A5 with A1: six covered groups in easy, so song k has 5 - k eligible distractor groups and
	// k = min(4, 6 - k): 4, 4, 4, 3, 2. Uncovered groups never count.
	@Test
	void choicesShrinkWhenTooFewGroupsAreLeft() {
		List<Spec> specs = new ArrayList<>(ChoiceFixture.coveredSingles(0, 6));
		specs.addAll(ChoiceFixture.uncoveredSingles(100, 10));
		Built built = ChoiceFixture.build(dir, specs);
		Set<Integer> pool = poolOf(built, a -> true);
		for (long seed = 0; seed < 100; seed++) {
			List<Drawn> game = new Game(built, seed).play(5, EASY);
			assertThat(game.stream().map(d -> d.choiceAnime().size()).toList()).as("seed " + seed)
					.containsExactly(4, 4, 4, 3, 2);
			assertChoiceRules(built, game, pool, "seed " + seed);
		}
	}

	// ---- item 2 and A5: the draw ----

	@Test
	void aChoiceRoomDrawsOnlyThemesWithCovers() {
		List<Spec> specs = new ArrayList<>();
		for (int a = 0; a < 20; a++) {
			specs.add(a % 2 == 0 ? ChoiceFixture.covered(a) : ChoiceFixture.uncovered(a));
		}
		Built built = ChoiceFixture.build(dir, specs);
		boolean chatDrewUncovered = false;
		for (long seed = 0; seed < 60; seed++) {
			for (Drawn d : new Game(built, seed).play(5, DrawFilter.NONE)) {
				assertThat(built.specs().get(d.anime()).cover()).as("seed " + seed).isTrue();
			}
			Game chat = new Game(built, seed);
			chat.room.start(5, DrawFilter.NONE, AnswerMode.CHAT);
			for (int k = 0; k < 5; k++) {
				String url = (String) chat.outbox.last("prepare").message().get("audioUrl");
				int themeId = chat.tokens.resolve(url.substring("/audio/".length())).orElseThrow();
				chatDrewUncovered |= !built.specs().get(built.animeIndexOfTheme(themeId)).cover();
				chat.room.ready(MINA.id(), k);
				chat.room.skip(MINA.id(), k);
				chat.scheduler.advance(Room.REVEAL_PAUSE);
			}
		}
		assertThat(chatDrewUncovered).as("a chat room still draws uncovered themes").isTrue();
	}

	private static List<Map<String, Object>> errorsOf(Game game) {
		return game.outbox.all().stream().filter(s -> "error".equals(s.type())).map(Sent::message).toList();
	}

	// Four covered single-theme groups and ten uncovered themes: normal counts covered themes only.
	@Test
	void notEnoughSongsCountsOnlyCoveredThemes() {
		List<Spec> specs = new ArrayList<>(ChoiceFixture.coveredSingles(0, 4));
		specs.addAll(ChoiceFixture.uncoveredSingles(100, 10));
		Built built = ChoiceFixture.build(dir, specs);
		Game game = new Game(built, 1);
		game.room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(game.outbox.all()).hasSize(1).allMatch(Sent::toAll);
		assertThat(errorsOf(game)).containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", 4));

		Game easy = new Game(built, 1);
		easy.room.start(5, EASY, AnswerMode.CHOICE);
		assertThat(easy.room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(errorsOf(easy)).containsExactly(
				Map.of("type", "error", "code", "not-enough-songs", "available", 4, "unit", "series"));

		Game chat = new Game(built, 1);
		chat.room.start(5, DrawFilter.NONE, AnswerMode.CHAT);
		assertThat(chat.room.phase()).as("the same catalog in a chat room").isEqualTo(Room.Phase.PREPARE);
	}

	// A5: three covered groups with three themes each (nine covered themes) are too few groups.
	@Test
	void aChoiceRoomNeedsFourCoveredGroups() {
		List<Spec> specs = new ArrayList<>();
		for (int g = 0; g < 3; g++) {
			specs.add(new Spec(g, 3, "OP", 2000, true));
		}
		specs.addAll(ChoiceFixture.uncoveredSingles(100, 10));
		Built built = ChoiceFixture.build(dir, specs);
		for (DrawFilter filter : List.of(DrawFilter.NONE, HARD)) {
			Game game = new Game(built, 2);
			game.room.start(5, filter, AnswerMode.CHOICE);
			assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(errorsOf(game)).as(filter.toString()).containsExactly(
					Map.of("type", "error", "code", "not-enough-songs", "available", 3, "unit", "series"));
			assertThat(game.outbox.ofType("settings")).isEmpty();
		}
		// One more covered group is enough.
		specs.add(new Spec(3, 1, "OP", 2000, true));
		Built four = ChoiceFixture.build(dir.resolve("four"), specs);
		Game game = new Game(four, 2);
		game.room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(game.room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- A2 and A9: distribution and shuffle ----

	// As RoomEasyUniformTest: 23 anime, the four BIG anime share BIG_GROUP and have ten themes each,
	// the other 19 have one theme and a group of their own; all covered and within easy.
	private static final Set<Integer> BIG_ANIME = Set.of(0, 6, 12, 18);

	private static final int BIG_GROUP = 0;

	private static List<Spec> bigGroupSpecs() {
		List<Spec> specs = new ArrayList<>();
		int next = 1;
		for (int a = 0; a < 23; a++) {
			specs.add(BIG_ANIME.contains(a) ? new Spec(BIG_GROUP, 10, "OP", 2000, true)
					: new Spec(next++, 1, "OP", 2000, true));
		}
		return specs;
	}

	// The first song of each seeded game: how often each group is a distractor when it could be one
	// (it is not the correct group), as a rate.
	private static Map<Integer, Double> distractorRates(Built built, DrawFilter filter, int games, int[] positions) {
		Map<Integer, Integer> eligible = new TreeMap<>();
		Map<Integer, Integer> shown = new TreeMap<>();
		for (long seed = 0; seed < games; seed++) {
			Game game = new Game(built, seed);
			game.room.start(5, filter, AnswerMode.CHOICE);
			Drawn d = game.current();
			if (positions != null) {
				positions[d.correctPosition()]++;
			}
			int correctGroup = built.groupOf(d.anime());
			Set<Integer> groups = new HashSet<>();
			for (int a : d.choiceAnime()) {
				if (a != d.anime()) {
					groups.add(built.groupOf(a));
				}
			}
			for (int g : allGroups(built)) {
				if (g == correctGroup) {
					continue;
				}
				eligible.merge(g, 1, Integer::sum);
				if (groups.contains(g)) {
					shown.merge(g, 1, Integer::sum);
				}
			}
		}
		Map<Integer, Double> rates = new TreeMap<>();
		for (int g : allGroups(built)) {
			rates.put(g, shown.getOrDefault(g, 0) / (double) eligible.get(g));
		}
		return rates;
	}

	private static Set<Integer> allGroups(Built built) {
		return new HashSet<>(built.specs().stream().map(Spec::group).toList());
	}

	private static double meanOfSmallGroups(Map<Integer, Double> rates) {
		return rates.entrySet().stream().filter(e -> e.getKey() != BIG_GROUP).mapToDouble(Map.Entry::getValue)
				.average().orElseThrow();
	}

	// Easy draws a remaining group uniformly, so the 40-theme group is a distractor as often as a
	// one-theme group; the correct choice is at each of the 4 positions 25 +- 3 % of the time.
	@Test
	void easyDistractorsAreUniformOverGroupsAndPositionsAreShuffled() {
		Built built = ChoiceFixture.build(dir, bigGroupSpecs());
		int games = 4000;
		int[] positions = new int[4];
		Map<Integer, Double> rates = distractorRates(built, EASY, games, positions);
		double ratio = rates.get(BIG_GROUP) / meanOfSmallGroups(rates);
		assertThat(ratio).as("big group rate / small group rate (expected 1); rates " + rates).isBetween(0.8, 1.25);
		for (int p = 0; p < 4; p++) {
			assertThat(positions[p] / (double) games).as("share of the correct choice at position " + p + " of "
					+ Arrays.toString(positions)).isBetween(0.22, 0.28);
		}
	}

	// Normal and hard draw a uniform song among the groups not yet chosen, so the 40-theme group is a
	// distractor far more often than a one-theme group (about 0.99 against 0.22).
	@Test
	void normalDistractorsFollowTheSongWeightedDraw() {
		Built built = ChoiceFixture.build(dir, bigGroupSpecs());
		Map<Integer, Double> rates = distractorRates(built, DrawFilter.NONE, 1500, null);
		double ratio = rates.get(BIG_GROUP) / meanOfSmallGroups(rates);
		assertThat(ratio).as("big group rate / small group rate (expected about 4.5); rates " + rates)
				.isGreaterThan(2.5);
	}

}
