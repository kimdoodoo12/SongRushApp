package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.GameProperties;
import com.songrush.audio.AudioTokens;
import com.songrush.game.Normalize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 126 (B-034, D-129) criterion 12, end to end: with play-to-limit on (the default since cycle 132) a real
// server keeps the reveal of a song decided early up until the music ends
// (docs/cycles/126-reveal-window.md). The data folder is written into a static @TempDir before the
// context loads: 8 anime (id 1000 + i, theme 5000 + i, its own group) with one cached theme of 30 s
// each, so a song decided right after startAt has a window of almost 30 s. (The shared WsFixture
// songs last 3 s, where the window is 5 s with the switch on or off.) Wall-clock bounds are loose: the
// point is "the reveal lasts until the deadline" versus "5 s". The API is described in
// GamePropertiesBindingTest and RoomPlayToLimitTest.
//
// Cycle 132 (B-037, D-129) criterion 2: play-to-limit is on by default, so this test sets no
// songrush.game property at all and runs with the default configuration
// (docs/cycles/132-play-to-limit-on.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlayToLimitSocketTest {

	private static final int SONGS = 8;

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final long DURATION_MS = 30_000;

	// Margin after startAt before the guess, so the guess cannot be early.
	private static final long AFTER_START_MS = 300;

	private static final long MIN_WINDOW_MS = 15_000;

	private static final long QUIET_MS = 8000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		writeCatalog(dataDir);
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

	@Autowired
	private GameProperties properties;

	@Autowired
	private AudioTokens tokens;

	private final List<TestClient> clients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(TestClient::close);
	}

	private static String title(int i) {
		return "Limit Anime " + i;
	}

	private static void writeCatalog(Path dir) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int i = 0; i < SONGS; i++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("en", List.of(title(i)));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 100000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Limit Reveal " + i);
				anime.add(a);
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.of(Normalize.normalize(title(i)))));

				String basename = "Limit" + i + "-OP1.ogg";
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", THEME_BASE + i);
				t.put("animeId", ANIME_BASE + i);
				t.put("type", "OP");
				t.put("slug", "OP1");
				t.put("song", "Limit Song " + i);
				t.put("artists", List.of("Limit Singer " + i));
				t.put("audio", Map.of("basename", basename, "size", 1));
				themes.add(t);
				drawSet.add(THEME_BASE + i);

				index.put(Integer.toString(THEME_BASE + i),
						Map.of("basename", basename, "durationMs", DURATION_MS, "size", 1));
				Files.write(cache.resolve(basename), new byte[1]);
			}
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("version", 1);
			root.put("anime", anime);
			root.put("themes", themes);
			root.put("groups", groups);
			root.put("drawSet", drawSet);
			Files.writeString(dir.resolve("catalog.json"), MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
			Files.writeString(cache.resolve("index.json"), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void sleepUntil(long epochMillis) throws InterruptedException {
		long left = epochMillis - System.currentTimeMillis();
		if (left > 0) {
			Thread.sleep(left);
		}
	}

	// Cycle 132: true with no property set, from the default.
	@Test
	void thePropertyBindsTrue() {
		assertThat(properties.playToLimit()).isTrue();
		assertThat(properties.prefetchOnReveal()).as("the other switch stays off").isFalse();
	}

	// Criterion 12
	@Test
	void aSongDecidedEarlyKeepsItsRevealUntilTheMusicEnds() throws Exception {
		TestClient client = TestClient.connect(port);
		clients.add(client);
		client.send("create", "nickname", "Tester", "songCount", 5);
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();

		client.send("ready", "index", 0);
		JsonNode start = client.await("start");
		long startAt = start.path("startAt").longValue();
		long deadlineAt = start.path("deadlineAt").longValue();
		assertThat(deadlineAt - startAt).as("the test's own 30 s songs").isEqualTo(DURATION_MS);

		sleepUntil(startAt + AFTER_START_MS);
		client.send("chat", "text", title(themeId - THEME_BASE));
		JsonNode correct = client.await("correct");
		assertThat(correct.path("name").asString(null)).isEqualTo("Tester");
		JsonNode reveal = client.await("reveal");
		long revealAt = System.currentTimeMillis();
		assertThat(reveal.path("index").intValue()).isEqualTo(0);
		assertThat(reveal.path("winner").asString(null)).isEqualTo("Tester");
		assertThat(revealAt).as("decided early").isLessThan(deadlineAt - MIN_WINDOW_MS);

		assertThat(reveal.path("playUntil").isIntegralNumber()).as("reveal has playUntil").isTrue();
		long playUntil = reveal.path("playUntil").longValue();
		assertThat(playUntil).as("playUntil is the song's deadline").isEqualTo(deadlineAt);
		assertThat(playUntil - revealAt).as("at least 15 s of music left").isGreaterThanOrEqualTo(MIN_WINDOW_MS);

		// With the default (play-to-limit on) nothing comes for 8 s; with play-to-limit=false the next
		// prepare would come 5 s after the reveal.
		client.assertNone(Set.of("prepare", "gameOver"), revealAt + QUIET_MS);
		assertThat(System.currentTimeMillis()).isGreaterThanOrEqualTo(revealAt + QUIET_MS);
		client.assertPingWorks();
	}

}
