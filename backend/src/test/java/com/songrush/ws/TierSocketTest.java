package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.Normalize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 063 (B-012) criterion 3: the tier field of create (solo) and start over the raw WebSocket at
// /ws on a real server (docs/cycles/063-tier-filter.md). The data folder is written into a static
// @TempDir before the context loads: entry i is anime 1000 + i with one cached theme 5000 + i of
// 3000 ms and a 1-byte audio file, even i OP and odd i ED, of year 2000. Its rank is i + 1; there
// are ENTRIES >= 510 of them, so a hard start (ranks 501+) has at least 5 songs (spec review). The
// current song is found by resolving the token in prepare.audioUrl with the AudioTokens bean.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TierSocketTest {

	private static final int ENTRIES = 520;

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final long DURATION_MS = 3000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// Bad tier values of criterion 3 (and Behaviour 3: any other value), each sent on its own.
	private static final List<Object> BAD_TIERS = Arrays.asList("medium", 1, null, "Easy", "", List.of("easy"),
			true);

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
	private AudioTokens tokens;

	@Autowired
	private Catalog catalog;

	private final List<TestClient> clients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(TestClient::close);
	}

	// ---- fixture ----

	private static String type(int i) {
		return i % 2 == 0 ? "OP" : "ED";
	}

	private static String basename(int i) {
		return "TierSock" + i + "-" + type(i) + "1.ogg";
	}

	private static void writeCatalog(Path dir) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int i = 0; i < ENTRIES; i++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of("Tier Ko " + i));
				titles.put("en", List.of("Tier Anime " + i));
				titles.put("ja", List.of("Tier Ja " + i));
				titles.put("romaji", List.of("Tier Romaji " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 500000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Tier Reveal " + i);
				a.put("year", 2000);
				anime.add(a);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", THEME_BASE + i);
				t.put("animeId", ANIME_BASE + i);
				t.put("type", type(i));
				t.put("slug", type(i) + "1");
				t.put("song", "Tier Song " + i);
				t.put("artists", List.of("Tier Singer " + i));
				t.put("audio", Map.of("basename", basename(i), "size", 1));
				themes.add(t);
				drawSet.add(THEME_BASE + i);

				index.put(Integer.toString(THEME_BASE + i),
						Map.of("basename", basename(i), "durationMs", DURATION_MS, "size", 1));
				Files.write(cache.resolve(basename(i)), new byte[1]);
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

	// ---- helpers ----

	private TestClient connect() throws Exception {
		TestClient client = TestClient.connect(port);
		clients.add(client);
		return client;
	}

	private static List<JsonNode> frames(TestClient client) {
		List<JsonNode> out = new ArrayList<>();
		for (String frame : client.received()) {
			out.add(TestClient.MAPPER.readTree(frame));
		}
		return out;
	}

	private static List<String> frameTypes(TestClient client) {
		return frames(client).stream().map(n -> n.path("type").asString(null)).toList();
	}

	private static List<String> frameTypesSince(TestClient client, int mark) {
		List<String> types = frameTypes(client);
		return types.subList(mark, types.size());
	}

	private static JsonNode settings(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "settings");
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return MAPPER.valueToTree(m);
	}

	private static int firstIndexOf(List<String> types, String type) {
		int i = types.indexOf(type);
		assertThat(i).as("a '" + type + "' frame in " + types).isGreaterThanOrEqualTo(0);
		return i;
	}

	// The next roster with exactly the given number of players; earlier rosters are skipped.
	private static JsonNode awaitRoster(TestClient client, int players) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			JsonNode roster = client.await("roster");
			if (roster.path("players").isArray() && roster.path("players").size() == players) {
				return roster;
			}
		}
		return fail("no roster with " + players + " players within " + TestClient.WAIT_MS + " ms");
	}

	private String createLobby(TestClient host) throws Exception {
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		return code;
	}

	// The rank (1-based draw-set position) of the song a prepare names.
	private int rankOf(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.drawSet().indexOf(theme.id()) + 1;
	}

	@Test
	void fixtureHasEnoughThemesForAHardGame() {
		assertThat(catalog.drawSet()).hasSize(ENTRIES);
		assertThat(catalog.candidates()).hasSize(ENTRIES);
		assertThat(ENTRIES - 500).isGreaterThanOrEqualTo(10);
	}

	// ---- bad-filter ----

	@Test
	void soloCreateWithABadTierIsBadFilterAndNoLobbyIsMade() throws Exception {
		TestClient client = connect();
		for (Object bad : BAD_TIERS) {
			client.send("create", "nickname", "Tester", "songCount", 5, "tier", bad);
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as("tier " + bad).isEqualTo("bad-filter");
			client.assertPingWorks();
		}
		assertThat(frameTypes(client)).as("no lobby was joined").doesNotContain("joined", "token", "roster",
				"settings", "prepare");
		client.send("create", "nickname", "Tester", "songCount", 5, "tier", "easy");
		assertThat(client.await("prepare").path("index").intValue()).isEqualTo(0);
	}

	@Test
	void soloCreateChecksTheSongCountBeforeTheTier() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 4, "tier", "medium");
		client.awaitError("bad-song-count");
		client.send("create", "nickname", "Tester", "songCount", 31, "tier", null);
		client.awaitError("bad-song-count");
		client.assertPingWorks();
	}

	@Test
	void aBadTierIsBadFilterEvenWithValidOtherFields() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("OP"), "yearFrom", 1990,
				"tier", "medium");
		client.awaitError("bad-filter");
		client.send("start", "songCount", 5, "types", List.of("ED"), "tier", 1);
		client.awaitError("bad-filter");
		client.assertPingWorks();
	}

	@Test
	void startWithABadTierIsBadFilterToTheSenderOnlyAndNothingStarts() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		String code = createLobby(host);
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);

		for (Object bad : BAD_TIERS) {
			host.send("start", "songCount", 5, "tier", bad);
			JsonNode error = host.await("error");
			assertThat(error.path("code").asString(null)).as("host tier " + bad).isEqualTo("bad-filter");
			// Checked in the handler before the lobby's not-host.
			guest.send("start", "songCount", 5, "tier", bad);
			error = guest.await("error");
			assertThat(error.path("code").asString(null)).as("guest tier " + bad).isEqualTo("bad-filter");
		}
		host.assertPingWorks();
		guest.assertPingWorks();
		for (TestClient client : List.of(host, guest)) {
			List<JsonNode> errors = frames(client).stream().filter(n -> "error".equals(n.path("type").asString(null)))
					.toList();
			assertThat(errors).as("each sender got exactly its own errors").hasSize(BAD_TIERS.size());
			assertThat(frameTypes(client)).doesNotContain("settings", "prepare");
		}
	}

	@Test
	void startWithABadTierWithoutARoomIsBadFilterBeforeNoRoom() throws Exception {
		TestClient client = connect();
		for (Object bad : BAD_TIERS) {
			client.send("start", "songCount", 5, "tier", bad);
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as("tier " + bad).isEqualTo("bad-filter");
		}
		client.send("start", "songCount", 5, "tier", "hard");
		client.awaitError("no-room");
	}

	// ---- normal is the same as no tier ----

	@Test
	void soloNormalIsLikeNoTier() throws Exception {
		for (boolean normal : new boolean[] {false, true}) {
			TestClient client = connect();
			if (normal) {
				client.send("create", "nickname", "Tester", "songCount", 5, "tier", "normal");
			}
			else {
				client.send("create", "nickname", "Tester", "songCount", 5);
			}
			assertThat(client.await("prepare").path("index").intValue()).isEqualTo(0);
			client.assertPingWorks();
			assertThat(frameTypes(client)).as("tier normal " + normal).doesNotContain("settings", "error");
			client.close();
		}
	}

	@Test
	void lobbyStartWithNormalSendsNoSettingsAndPrepareComesFirst() throws Exception {
		for (boolean normal : new boolean[] {false, true}) {
			TestClient host = connect();
			createLobby(host);
			awaitRoster(host, 1);
			host.assertPingWorks();
			int mark = host.received().size();
			if (normal) {
				host.send("start", "songCount", 5, "tier", "normal");
			}
			else {
				host.send("start", "songCount", 5);
			}
			assertThat(host.await("prepare").path("index").intValue()).isEqualTo(0);
			host.assertPingWorks();
			List<String> since = frameTypesSince(host, mark);
			assertThat(since).as("tier normal " + normal).isNotEmpty();
			assertThat(since.get(0)).as("first frame after start in " + since).isEqualTo("prepare");
			assertThat(frameTypes(host)).doesNotContain("settings", "error");
			host.close();
		}
	}

	// ---- a valid tier ----

	@Test
	void aLobbyStartWithHardSendsSettingsToEveryMemberBeforePrepare() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		String code = createLobby(host);
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);

		host.send("start", "songCount", 5, "tier", "hard");
		for (TestClient client : List.of(host, guest)) {
			assertThat(client.await("settings")).isEqualTo(settings("tier", "hard"));
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(0);
			assertThat(rankOf(prepare)).as("hard draws rank 501+").isBetween(501, ENTRIES);
			List<String> types = frameTypes(client);
			assertThat(firstIndexOf(types, "settings")).isLessThan(firstIndexOf(types, "prepare"));
			assertThat(types.stream().filter("settings"::equals).count()).isEqualTo(1);
		}
	}

	@Test
	void soloEasyWithTypesGetsSettingsWithTheTierLastThenPrepare() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("ED"), "tier", "easy");
		JsonNode settings = client.await("settings");
		assertThat(settings).isEqualTo(settings("types", List.of("ED"), "tier", "easy"));
		List<String> keys = new ArrayList<>();
		settings.properties().forEach(e -> keys.add(e.getKey()));
		assertThat(keys).containsExactly("type", "types", "tier");
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
		int rank = rankOf(prepare);
		assertThat(rank).isBetween(1, 300);
		assertThat(type(rank - 1)).isEqualTo("ED");
		List<String> types = frameTypes(client);
		assertThat(types.get(firstIndexOf(types, "settings") + 1)).as("frame after settings in " + types)
				.isEqualTo("prepare");
	}

}
