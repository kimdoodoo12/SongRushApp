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
// /ws on a real server (docs/cycles/063-tier-filter.md), updated for cycle 094 (B-021): tiers by anime
// rank, easy 1-100, normal 1-300, hard all, and the easy shortage counted in series
// (docs/cycles/094-nested-tiers.md). The data folder is written into a static @TempDir before the
// context loads: anime a (0-based, id 1000 + a, its own group, anime rank a + 1) has four cached
// themes 5000 + 10 * a + j (j = 0..3, OP for even j) when a < 100, else one theme 5000 + 10 * a (OP
// for even a); every theme lasts 3000 ms and has a 1-byte audio file. Anime ranks 96-100 are of 1998
// and sit at draw-set positions 381-400 (outside a theme-rank 1-300 cut); anime ranks 301-305 are of
// 1997; the rest are of 2000. Year-narrowed starts make each tier check deterministic. The current
// song is found by resolving the token in prepare.audioUrl with the AudioTokens bean.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TierSocketTest {

	private static final int ANIME_COUNT = 310;

	// Anime with four themes each: indexes 0..MULTI - 1.
	private static final int MULTI = 100;

	private static final int THEME_COUNT = 4 * MULTI + (ANIME_COUNT - MULTI);

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

	private static int themesOf(int a) {
		return a < MULTI ? 4 : 1;
	}

	private static int themeId(int a, int j) {
		return THEME_BASE + 10 * a + j;
	}

	// The type of theme j of anime a.
	private static String type(int a, int j) {
		int n = a < MULTI ? j : a;
		return n % 2 == 0 ? "OP" : "ED";
	}

	private static int year(int a) {
		if (95 <= a && a <= 99) {
			return 1998;
		}
		if (300 <= a && a <= 304) {
			return 1997;
		}
		return 2000;
	}

	private static String basename(int a, int j) {
		return "TierSock" + a + "-" + type(a, j) + (j + 1) + ".ogg";
	}

	// The fixture's anime rank of a theme id, from the model.
	private static int animeRankOf(int themeId) {
		return (themeId - THEME_BASE) / 10 + 1;
	}

	private static String typeOf(int themeId) {
		return type((themeId - THEME_BASE) / 10, (themeId - THEME_BASE) % 10);
	}

	private static void writeCatalog(Path dir) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			for (int a = 0; a < ANIME_COUNT; a++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("ko", List.of("Tier Ko " + a));
				titles.put("en", List.of("Tier Anime " + a));
				titles.put("ja", List.of("Tier Ja " + a));
				titles.put("romaji", List.of("Tier Romaji " + a));

				Map<String, Object> an = new LinkedHashMap<>();
				an.put("id", ANIME_BASE + a);
				an.put("anilistId", 500000 + a);
				an.put("group", GROUP_BASE + a);
				an.put("titles", titles);
				an.put("reveal", "Tier Reveal " + a);
				an.put("year", year(a));
				anime.add(an);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + a, "accepted", List.copyOf(accepted)));

				for (int j = 0; j < themesOf(a); j++) {
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", themeId(a, j));
					t.put("animeId", ANIME_BASE + a);
					t.put("type", type(a, j));
					t.put("slug", type(a, j) + (j + 1));
					t.put("song", "Tier Song " + a + "-" + j);
					t.put("artists", List.of("Tier Singer " + a));
					t.put("audio", Map.of("basename", basename(a, j), "size", 1));
					themes.add(t);
					drawSet.add(themeId(a, j));

					index.put(Integer.toString(themeId(a, j)),
							Map.of("basename", basename(a, j), "durationMs", DURATION_MS, "size", 1));
					Files.write(cache.resolve(basename(a, j)), new byte[1]);
				}
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

	// The first received frame of the type, whatever order it arrived in, within WAIT_MS. Reads the
	// list of every frame, so it does not consume the frames that await() reads (as in
	// ReconnectSocketTest).
	private static JsonNode firstReceived(TestClient client, String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			for (JsonNode node : frames(client)) {
				if (node != null && node.isObject() && type.equals(node.path("type").asString(null))) {
					return node;
				}
			}
			Thread.sleep(20);
		}
		return fail("no '" + type + "' frame within " + TestClient.WAIT_MS + " ms");
	}

	// Also waits for the host's token, which the handler sends after the lobby's joined and roster:
	// otherwise a later received().size() mark could be taken before it arrives (cycle 075).
	private String createLobby(TestClient host) throws Exception {
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		firstReceived(host, "token");
		return code;
	}

	// The theme id of the song a prepare names.
	private int themeOf(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return theme.id();
	}

	// not-enough-songs with the count; unit is left out when null.
	private static JsonNode notEnough(int available, String unit) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "error");
		m.put("code", "not-enough-songs");
		m.put("available", available);
		if (unit != null) {
			m.put("unit", unit);
		}
		return MAPPER.valueToTree(m);
	}

	@Test
	void fixtureRanksAnimeBehindTheirDrawSetPositions() {
		assertThat(catalog.drawSet()).hasSize(THEME_COUNT);
		assertThat(catalog.candidates()).hasSize(THEME_COUNT);
		// Anime rank 100's themes are at positions 397-400, past a theme-rank 300 cut.
		assertThat(catalog.rank(themeId(99, 0))).isEqualTo(397);
		assertThat(catalog.animeRank(themeId(99, 0))).isEqualTo(100);
		assertThat(catalog.animeRank(themeId(99, 3))).isEqualTo(100);
		assertThat(catalog.animeRank(themeId(95, 1))).isEqualTo(96);
		assertThat(catalog.animeRank(themeId(300, 0))).isEqualTo(301);
		assertThat(catalog.animeRank(themeId(304, 0))).isEqualTo(305);
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

		// Hard of 1997: exactly anime ranks 301-305, outside normal.
		host.send("start", "songCount", 5, "yearFrom", 1997, "yearTo", 1997, "tier", "hard");
		for (TestClient client : List.of(host, guest)) {
			assertThat(client.await("settings")).isEqualTo(settings("yearFrom", 1997, "yearTo", 1997, "tier", "hard"));
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(0);
			assertThat(animeRankOf(themeOf(prepare))).as("hard of 1997 draws anime rank 301-305").isBetween(301, 305);
			List<String> types = frameTypes(client);
			assertThat(firstIndexOf(types, "settings")).isLessThan(firstIndexOf(types, "prepare"));
			assertThat(types.stream().filter("settings"::equals).count()).isEqualTo(1);
		}
	}

	@Test
	void soloEasyWithTypesGetsSettingsWithTheTierLastThenPrepare() throws Exception {
		TestClient client = connect();
		// Easy ED of 1998: anime ranks 96-100, two EDs each, five groups; their draw-set positions are
		// 381-400, so a theme-rank cut would leave nothing.
		client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("ED"), "yearFrom", 1998,
				"yearTo", 1998, "tier", "easy");
		JsonNode settings = client.await("settings");
		assertThat(settings)
				.isEqualTo(settings("types", List.of("ED"), "yearFrom", 1998, "yearTo", 1998, "tier", "easy"));
		List<String> keys = new ArrayList<>();
		settings.properties().forEach(e -> keys.add(e.getKey()));
		assertThat(keys).containsExactly("type", "types", "yearFrom", "yearTo", "tier");
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
		int themeId = themeOf(prepare);
		assertThat(animeRankOf(themeId)).isBetween(96, 100);
		assertThat(typeOf(themeId)).isEqualTo("ED");
		List<String> types = frameTypes(client);
		assertThat(types.get(firstIndexOf(types, "settings") + 1)).as("frame after settings in " + types)
				.isEqualTo("prepare");
	}

	// ---- not-enough-songs by tier ----

	@Test
	void soloEasyWithFewerGroupsThanSongsCountsSeries() throws Exception {
		TestClient client = connect();
		// Easy of 1998: anime ranks 96-100, 20 themes in 5 groups.
		client.send("create", "nickname", "Tester", "songCount", 6, "yearFrom", 1998, "yearTo", 1998, "tier", "easy");
		assertThat(client.await("error")).isEqualTo(notEnough(5, "series"));
		client.assertPingWorks();
		assertThat(frameTypes(client)).doesNotContain("settings", "prepare");
	}

	@Test
	void lobbyNormalAndHardCountSongsWithoutAUnit() throws Exception {
		TestClient host = connect();
		createLobby(host);
		awaitRoster(host, 1);
		// Normal of 1997: anime ranks 301-305 are outside normal, so nothing matches.
		host.send("start", "songCount", 5, "yearFrom", 1997, "yearTo", 1997);
		assertThat(host.await("error")).isEqualTo(notEnough(0, null));
		host.send("start", "songCount", 5, "yearFrom", 1997, "yearTo", 1997, "tier", "normal");
		assertThat(host.await("error")).isEqualTo(notEnough(0, null));
		// Hard of 1997: 5 songs, one short of 6.
		host.send("start", "songCount", 6, "yearFrom", 1997, "yearTo", 1997, "tier", "hard");
		assertThat(host.await("error")).isEqualTo(notEnough(5, null));
		// Normal OP of 1998: anime ranks 96-100 with two OPs each, 10 songs in 5 anime.
		host.send("start", "songCount", 11, "types", List.of("OP"), "yearFrom", 1998, "yearTo", 1998);
		assertThat(host.await("error")).isEqualTo(notEnough(10, null));
		host.assertPingWorks();
		assertThat(frameTypes(host)).doesNotContain("settings", "prepare");
	}

}
