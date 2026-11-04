package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.songrush.catalog.CatalogLoader;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 065 (B-013): a catalog theme without a "type" key never breaks a draw
// (docs/cycles/065-null-type-guard.md). The catalog is written into a @TempDir and loaded with
// CatalogLoader: entries 0..4 are OP, entry 5 has no type, all grouped, cached with 1-byte files.
class DrawFilterNullTypeTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final int OPS = 5;

	// The index of the theme with no type.
	private static final int NULL_TYPE = OPS;

	private static final DrawFilter OP_ONLY = new DrawFilter(Set.of("OP"), null, null);

	@TempDir
	Path dir;

	private Catalog catalog;

	@BeforeEach
	void load() throws IOException {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		Path cache = Files.createDirectories(dir.resolve("audio-cache"));
		for (int i = 0; i <= NULL_TYPE; i++) {
			Map<String, List<String>> titles = new LinkedHashMap<>();
			titles.put("ko", List.of("NullType Ko " + i));
			titles.put("en", List.of("NullType Anime " + i));
			titles.put("ja", List.of("NullType Ja " + i));
			titles.put("romaji", List.of("NullType Romaji " + i));

			Map<String, Object> a = new LinkedHashMap<>();
			a.put("id", ANIME_BASE + i);
			a.put("anilistId", 300000 + i);
			a.put("group", GROUP_BASE + i);
			a.put("titles", titles);
			a.put("reveal", "NullType Reveal " + i);
			a.put("year", 2000 + i);
			anime.add(a);

			LinkedHashSet<String> accepted = new LinkedHashSet<>();
			titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
			groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

			String basename = "NullType" + i + ".ogg";
			Map<String, Object> t = new LinkedHashMap<>();
			t.put("id", THEME_BASE + i);
			t.put("animeId", ANIME_BASE + i);
			if (i != NULL_TYPE) {
				t.put("type", "OP");
				t.put("slug", "OP1");
			}
			t.put("song", "NullType Song " + i);
			t.put("artists", List.of("NullType Singer " + i));
			t.put("audio", Map.of("basename", basename, "size", 1));
			themes.add(t);
			drawSet.add(THEME_BASE + i);

			index.put(Integer.toString(THEME_BASE + i), Map.of("basename", basename, "durationMs", 60000, "size", 1));
			Files.write(cache.resolve(basename), new byte[1]);
		}
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("version", 1);
		root.put("anime", anime);
		root.put("themes", themes);
		root.put("groups", groups);
		root.put("drawSet", drawSet);
		Path catalogJson = dir.resolve("catalog.json");
		Files.writeString(catalogJson, MAPPER.writeValueAsString(root));
		Path indexJson = cache.resolve("index.json");
		Files.writeString(indexJson, MAPPER.writeValueAsString(index));
		catalog = CatalogLoader.load(catalogJson, indexJson);
		assertThat(catalog.candidates()).as("every fixture theme is a candidate").hasSize(NULL_TYPE + 1);
	}

	private Theme theme(int i) {
		return catalog.theme(THEME_BASE + i).orElseThrow();
	}

	private Anime animeOf(Theme theme) {
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	// ---- criterion 1: DrawFilter.matches ----

	@Test
	void theFixtureThemeWithoutATypeKeyLoadsWithANullType() {
		assertThat(theme(NULL_TYPE).type()).isNull();
		for (int i = 0; i < OPS; i++) {
			assertThat(theme(i).type()).isEqualTo("OP");
		}
	}

	@Test
	void aTypesFilterDoesNotMatchANullTypeThemeAndDoesNotThrow() {
		Theme nullType = theme(NULL_TYPE);
		Anime anime = animeOf(nullType);
		assertThatCode(() -> OP_ONLY.matches(nullType, anime)).doesNotThrowAnyException();
		assertThat(OP_ONLY.matches(nullType, anime)).isFalse();
		DrawFilter withYears = new DrawFilter(Set.of("OP", "ED"), 1990, 2030);
		assertThatCode(() -> withYears.matches(nullType, anime)).doesNotThrowAnyException();
		assertThat(withYears.matches(nullType, anime)).isFalse();
	}

	@Test
	void aTypesFilterStillMatchesTypedThemes() {
		for (int i = 0; i < OPS; i++) {
			assertThat(OP_ONLY.matches(theme(i), animeOf(theme(i)))).as("OP " + i).isTrue();
		}
	}

	@Test
	void withoutTypesANullTypeThemeStillMatches() {
		Theme nullType = theme(NULL_TYPE);
		Anime anime = animeOf(nullType);
		assertThat(DrawFilter.NONE.matches(nullType, anime)).isTrue();
		assertThat(new DrawFilter(null, 1990, 2030).matches(nullType, anime)).isTrue();
	}

	// ---- criterion 2: Room.start with the null-type catalog ----

	private Room room(RecordingOutbox outbox, AudioTokens tokens, MutableClock clock) {
		return new Room(catalog, tokens, new ManualScheduler(clock), clock, new Random(65), outbox, List.of(MINA));
	}

	@Test
	void startWithOpOnlyAndMoreSongsThanOpsReportsTheOpCount() {
		MutableClock clock = new MutableClock();
		RecordingOutbox outbox = new RecordingOutbox();
		Room room = room(outbox, new AudioTokens(clock), clock);
		assertThatCode(() -> room.start(OPS + 1, OP_ONLY)).doesNotThrowAnyException();
		assertThat(outbox.all()).extracting(Sent::message)
				.containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", OPS));
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	@Test
	void startWithOpOnlyDrawsOnlyOpThemes() {
		for (long seed = 0; seed < 50; seed++) {
			MutableClock clock = new MutableClock();
			ManualScheduler scheduler = new ManualScheduler(clock);
			RecordingOutbox outbox = new RecordingOutbox();
			AudioTokens tokens = new AudioTokens(clock);
			Room room = new Room(catalog, tokens, scheduler, clock, new Random(seed), outbox, List.of(MINA));
			assertThatCode(() -> room.start(OPS, OP_ONLY)).as("seed " + seed).doesNotThrowAnyException();
			Set<Integer> drawn = new TreeSet<>();
			for (int k = 0; k < OPS; k++) {
				assertThat(room.phase()).as("seed " + seed + " song " + k).isEqualTo(Room.Phase.PREPARE);
				String url = (String) outbox.last("prepare").message().get("audioUrl");
				assertThat(url).startsWith("/audio/");
				int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
				assertThat(themeId).as("seed " + seed).isNotEqualTo(THEME_BASE + NULL_TYPE);
				assertThat(catalog.theme(themeId).orElseThrow().type()).isEqualTo("OP");
				drawn.add(themeId - THEME_BASE);
				room.ready(MINA.id(), k);
				room.skip(MINA.id(), k);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(drawn).containsExactly(0, 1, 2, 3, 4);
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		}
	}

}
