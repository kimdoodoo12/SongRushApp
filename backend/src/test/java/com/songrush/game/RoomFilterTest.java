package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 060 (B-011) criterion 2: Room.start(songCount, DrawFilter) on the cycle 036 fakes (fake
// clock, manual scheduler, seeded Random, recording Outbox, real AudioTokens) and a FilterFixture
// catalog with known theme types and anime years. Each drawn song is found by resolving the token
// in its prepare.audioUrl (docs/cycles/060-draw-filters.md).
class RoomFilterTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int DRAWS = 200;

	private static final int SONGS = 5;

	// 0..17 grouped, even OP / odd ED, year 1990 + i; 18 OP and 19 ED grouped without a year;
	// 20 OP and 21 ED of 2000 without a group (never drawable).
	private static final List<FilterFixture.Entry> ENTRIES = entries();

	private static final int GROUPED = 20;

	private static List<FilterFixture.Entry> entries() {
		List<FilterFixture.Entry> out = new ArrayList<>();
		for (int i = 0; i < 18; i++) {
			out.add(i % 2 == 0 ? FilterFixture.op(1990 + i) : FilterFixture.ed(1990 + i));
		}
		out.add(FilterFixture.op(null));
		out.add(FilterFixture.ed(null));
		out.add(new FilterFixture.Entry("OP", 2000, false));
		out.add(new FilterFixture.Entry("ED", 2000, false));
		return out;
	}

	@TempDir
	Path dir;

	private Catalog catalog;

	@BeforeEach
	void load() {
		catalog = FilterFixture.build(dir, ENTRIES);
	}

	// One room on fresh fakes, seeded.
	private final class Game {

		final MutableClock clock = new MutableClock();

		final ManualScheduler scheduler = new ManualScheduler(clock);

		final RecordingOutbox outbox = new RecordingOutbox();

		final AudioTokens tokens = new AudioTokens(clock);

		final Room room;

		Game(long seed) {
			room = new Room(catalog, tokens, scheduler, clock, new Random(seed), outbox, List.of(MINA));
		}

		Theme currentTheme() {
			String url = (String) outbox.last("prepare").message().get("audioUrl");
			assertThat(url).startsWith("/audio/");
			int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
			return catalog.theme(themeId).orElseThrow();
		}

		// From the first prepare: every song is readied and skipped, then the reveal pause runs.
		// Returns the drawn fixture indexes in song order.
		List<Integer> playToGameOver(int count) {
			List<Integer> drawn = new ArrayList<>();
			for (int k = 0; k < count; k++) {
				assertThat(room.phase()).as("song " + k).isEqualTo(Room.Phase.PREPARE);
				assertThat(room.songIndex()).isEqualTo(k);
				Theme theme = currentTheme();
				drawn.add(FilterFixture.indexOfTheme(theme.id()));
				room.ready(MINA.id(), k);
				assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
				room.skip(MINA.id(), k);
				assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
				Map<String, Object> reveal = outbox.last("reveal").message();
				assertThat(reveal.get("index")).isEqualTo(k);
				assertThat(reveal.get("themeType")).isEqualTo(theme.type());
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(outbox.ofType("gameOver")).hasSize(1);
			return drawn;
		}

	}

	// The fixture indexes a filter must allow, worked out from ENTRIES (not from DrawFilter).
	private static Set<Integer> expected(Set<String> types, Integer from, Integer to) {
		Set<Integer> out = new TreeSet<>();
		for (int i = 0; i < ENTRIES.size(); i++) {
			FilterFixture.Entry e = ENTRIES.get(i);
			if (!e.grouped()) {
				continue;
			}
			if (types != null && !types.contains(e.type())) {
				continue;
			}
			if ((from != null || to != null) && e.year() == null) {
				continue;
			}
			if (from != null && e.year() < from) {
				continue;
			}
			if (to != null && e.year() > to) {
				continue;
			}
			out.add(i);
		}
		return out;
	}

	private static Map<String, Object> settings(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "settings");
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	// ---- every drawn song matches ----

	@Test
	void everyDrawnSongMatchesTheFilterOverManySeededDraws() {
		List<DrawFilter> filters = List.of(
				new DrawFilter(Set.of("OP"), null, null),
				new DrawFilter(Set.of("ED"), null, null),
				new DrawFilter(Set.of("OP", "ED"), null, null),
				new DrawFilter(null, 2000, null),
				new DrawFilter(null, null, 1999),
				new DrawFilter(null, 1994, 2003),
				new DrawFilter(Set.of("OP"), 1992, 2005),
				new DrawFilter(Set.of("ED"), 1991, 1999));
		for (DrawFilter filter : filters) {
			Set<Integer> allowed = expected(filter.types(), filter.yearFrom(), filter.yearTo());
			assertThat(allowed.size()).as("fixture allows enough for " + filter).isGreaterThanOrEqualTo(SONGS);
			Set<Integer> seen = new TreeSet<>();
			for (long seed = 0; seed < DRAWS; seed++) {
				Game game = new Game(seed);
				game.room.start(SONGS, filter);
				List<Integer> drawn = game.playToGameOver(SONGS);
				assertThat(drawn).as(filter + " seed " + seed).doesNotHaveDuplicates();
				for (int i : drawn) {
					assertThat(allowed).as(filter + " seed " + seed + " drew " + i).contains(i);
					Theme theme = catalog.theme(FilterFixture.themeId(i)).orElseThrow();
					Anime anime = catalog.anime(theme.animeId()).orElseThrow();
					assertThat(filter.matches(theme, anime)).isTrue();
				}
				seen.addAll(drawn);
			}
			assertThat(seen).as("every allowed song is drawn at some point for " + filter)
					.containsExactlyElementsOf(allowed);
		}
	}

	@Test
	void aFilterAllowingExactlyTheCountDrawsExactlyThoseSongs() {
		// OP of 1990..1998: indexes 0, 2, 4, 6, 8.
		DrawFilter filter = new DrawFilter(Set.of("OP"), 1990, 1998);
		assertThat(expected(filter.types(), 1990, 1998)).containsExactly(0, 2, 4, 6, 8);
		Game game = new Game(60);
		game.room.start(SONGS, filter);
		assertThat(new TreeSet<>(game.playToGameOver(SONGS))).containsExactly(0, 2, 4, 6, 8);
	}

	// ---- settings ----

	@Test
	void withAFilterSettingsGoesToAllOnceBeforeTheFirstPrepareWithExactlyTheSetFields() {
		DrawFilter filter = new DrawFilter(Set.of("ED", "OP"), 1992, 2005);
		Game game = new Game(1);
		int mark = game.outbox.mark();
		game.room.start(SONGS, filter);
		List<Sent> sent = game.outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("settings", "prepare");
		assertThat(sent.get(0).toAll()).isTrue();
		Map<String, Object> message = sent.get(0).message();
		assertThat(message).isEqualTo(settings("types", List.of("OP", "ED"), "yearFrom", 1992, "yearTo", 2005));
		assertThat(new ArrayList<>(message.keySet())).containsExactly("type", "types", "yearFrom", "yearTo");
		game.playToGameOver(SONGS);
		assertThat(game.outbox.ofType("settings")).as("settings once per game").hasSize(1);
	}

	@Test
	void settingsCarriesOnlyTheFieldsThatAreSet() {
		Map<DrawFilter, Map<String, Object>> cases = new LinkedHashMap<>();
		cases.put(new DrawFilter(Set.of("OP"), null, null), settings("types", List.of("OP")));
		cases.put(new DrawFilter(Set.of("ED"), null, null), settings("types", List.of("ED")));
		cases.put(new DrawFilter(null, 2000, null), settings("yearFrom", 2000));
		cases.put(new DrawFilter(null, null, 1999), settings("yearTo", 1999));
		cases.put(new DrawFilter(null, 1994, 2003), settings("yearFrom", 1994, "yearTo", 2003));
		cases.put(new DrawFilter(Set.of("OP"), null, 2005), settings("types", List.of("OP"), "yearTo", 2005));
		for (Map.Entry<DrawFilter, Map<String, Object>> c : cases.entrySet()) {
			Game game = new Game(2);
			game.room.start(SONGS, c.getKey());
			List<Sent> sent = game.outbox.all();
			assertThat(sent).extracting(Sent::type).as(c.getKey().toString()).containsExactly("settings", "prepare");
			assertThat(sent.get(0).toAll()).isTrue();
			assertThat(sent.get(0).message()).as(c.getKey().toString()).isEqualTo(c.getValue());
			assertThat(new ArrayList<>(sent.get(0).message().keySet()))
					.containsExactlyElementsOf(c.getValue().keySet());
			assertThat(game.room.settings()).isEqualTo(c.getKey());
		}
	}

	@Test
	void settingsReturnsTheFilterOfTheGame() {
		DrawFilter filter = new DrawFilter(Set.of("OP"), 1990, 2010);
		Game game = new Game(3);
		game.room.start(SONGS, filter);
		assertThat(game.room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(game.room.settings()).isEqualTo(filter);
	}

	// ---- no filter ----

	@Test
	void withNoneTheFirstMessageIsPrepareAndNoSettingsIsEverSent() {
		for (boolean explicit : new boolean[] {false, true}) {
			Game game = new Game(4);
			if (explicit) {
				game.room.start(SONGS, DrawFilter.NONE);
			}
			else {
				game.room.start(SONGS);
			}
			List<Sent> sent = game.outbox.all();
			assertThat(sent).extracting(Sent::type).as("explicit NONE " + explicit).containsExactly("prepare");
			assertThat(game.room.settings()).isEqualTo(DrawFilter.NONE);
			List<Integer> drawn = game.playToGameOver(SONGS);
			assertThat(expected(null, null, null)).containsAll(drawn);
			assertThat(game.outbox.ofType("settings")).isEmpty();
		}
	}

	@Test
	void withNoneEveryGroupedSongCanBeDrawnIncludingThoseWithoutAYear() {
		Set<Integer> seen = new TreeSet<>();
		for (long seed = 0; seed < DRAWS; seed++) {
			Game game = new Game(seed);
			game.room.start(SONGS);
			seen.addAll(game.playToGameOver(SONGS));
		}
		assertThat(seen).hasSize(GROUPED).containsExactlyElementsOf(expected(null, null, null));
	}

	// ---- too few songs ----

	@Test
	void aTooNarrowFilterIsRejectedWithTheNarrowedCount() {
		Map<DrawFilter, Integer> cases = new LinkedHashMap<>();
		// ED of 2005..2007: indexes 15 and 17.
		cases.put(new DrawFilter(Set.of("ED"), 2005, 2007), 2);
		// 1990..1993: indexes 0..3.
		cases.put(new DrawFilter(null, 1990, 1993), 4);
		cases.put(new DrawFilter(Set.of("OP"), 2100, null), 0);
		cases.put(new DrawFilter(null, null, 1950), 0);
		for (Map.Entry<DrawFilter, Integer> c : cases.entrySet()) {
			DrawFilter filter = c.getKey();
			assertThat(expected(filter.types(), filter.yearFrom(), filter.yearTo())).hasSize(c.getValue());
			Game game = new Game(5);
			game.room.start(SONGS, filter);
			List<Sent> sent = game.outbox.all();
			assertThat(sent).as(filter.toString()).hasSize(1);
			assertThat(sent.get(0).toAll()).isTrue();
			assertThat(sent.get(0).message())
					.isEqualTo(Map.of("type", "error", "code", "not-enough-songs", "available", c.getValue()));
			assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(game.room.songIndex()).isEqualTo(-1);
			assertThat(game.scheduler.pending()).isZero();
		}
	}

	@Test
	void theNarrowedCountIsComparedWithTheSongCount() {
		// OP: 10 grouped songs.
		DrawFilter op = new DrawFilter(Set.of("OP"), null, null);
		Game tooMany = new Game(6);
		tooMany.room.start(11, op);
		assertThat(tooMany.outbox.all()).extracting(Sent::message)
				.containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", 10));
		Game enough = new Game(6);
		enough.room.start(10, op);
		assertThat(enough.outbox.all()).extracting(Sent::type).containsExactly("settings", "prepare");
		assertThat(enough.outbox.last("prepare").message().get("total")).isEqualTo(10);
	}

	@Test
	void withNoneTooFewSongsCarriesTheGroupedCount() {
		Game game = new Game(7);
		game.room.start(21);
		assertThat(game.outbox.all()).extracting(Sent::message)
				.containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", GROUPED));
		assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	@Test
	void aBadSongCountWithAFilterSendsNoSettings() {
		Game game = new Game(8);
		game.room.start(4, new DrawFilter(Set.of("OP"), null, null));
		assertThat(game.outbox.all()).extracting(Sent::message)
				.containsExactly(Map.of("type", "error", "code", "bad-song-count"));
		assertThat(game.room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

}
