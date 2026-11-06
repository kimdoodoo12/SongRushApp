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
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.Normalize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 060 (B-011) criterion 3: draw filters over the raw WebSocket at /ws on a real server
// (docs/cycles/060-draw-filters.md). The data folder is written into a static @TempDir before the
// context loads (no static resources, spec review H1): entry i is anime 1000 + i with one theme
// 5000 + i of 3000 ms, even i OP and odd i ED of year 1990 + i for i < 16, and entry 16 an OP with no
// year. The current song is found by resolving the token in prepare.audioUrl with the AudioTokens
// bean and reading the Catalog bean.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FilterSocketTest {

	private static final int WITH_YEAR = 16;

	private static final int ENTRIES = WITH_YEAR + 1;

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final long DURATION_MS = 3000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// The bad filter fields of criterion 3, each sent on its own.
	private static final List<Object[]> BAD_FILTERS = List.of(
			new Object[] {"types", List.of()},
			new Object[] {"types", List.of("OP", "OP")},
			new Object[] {"types", List.of("IN")},
			new Object[] {"types", "OP"},
			new Object[] {"yearFrom", 1949},
			new Object[] {"yearTo", 2101},
			new Object[] {"yearFrom", 2010, "yearTo", 2000},
			new Object[] {"yearFrom", 2000.5},
			new Object[] {"yearFrom", "2000"},
			// Behaviour 3: an explicit null and a non-string element are bad too (review nit).
			new Object[] {"types", null},
			new Object[] {"yearFrom", null},
			new Object[] {"yearTo", null},
			new Object[] {"types", List.of("OP", 1)},
			new Object[] {"types", List.of("op")},
			new Object[] {"yearTo", "2000"});

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
		return i == WITH_YEAR || i % 2 == 0 ? "OP" : "ED";
	}

	private static String basename(int i) {
		return "FilterSock" + i + "-" + type(i) + "1.ogg";
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
				titles.put("ko", List.of("Filter Ko " + i));
				titles.put("en", List.of("Filter Anime " + i));
				titles.put("ja", List.of("Filter Ja " + i));
				titles.put("romaji", List.of("Filter Romaji " + i));

				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 300000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Filter Reveal " + i);
				if (i < WITH_YEAR) {
					a.put("year", 1990 + i);
				}
				anime.add(a);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

				int size = 100 + i;
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", THEME_BASE + i);
				t.put("animeId", ANIME_BASE + i);
				t.put("type", type(i));
				t.put("slug", type(i) + "1");
				t.put("song", "Filter Song " + i);
				t.put("artists", List.of("Filter Singer " + i));
				t.put("audio", Map.of("basename", basename(i), "size", size));
				themes.add(t);
				drawSet.add(THEME_BASE + i);

				index.put(Integer.toString(THEME_BASE + i),
						Map.of("basename", basename(i), "durationMs", DURATION_MS, "size", size));
				Files.write(cache.resolve(basename(i)), WsFixture.audioBytes(THEME_BASE + i, size));
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

	private static Object[] concat(Object[] first, Object[] second) {
		Object[] out = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, out, first.length, second.length);
		return out;
	}

	private static String describe(Object[] fields) {
		return Arrays.deepToString(fields);
	}

	private static JsonNode settings(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "settings");
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return MAPPER.valueToTree(m);
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

	private Theme themeOf(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		return catalog.theme(themeId).orElseThrow();
	}

	// ---- bad-filter: solo create ----

	@Test
	void soloCreateWithABadFilterIsBadFilterAndNoLobbyIsMade() throws Exception {
		TestClient client = connect();
		for (Object[] bad : BAD_FILTERS) {
			client.send("create", concat(new Object[] {"nickname", "Tester", "songCount", 5}, bad));
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as(describe(bad)).isEqualTo("bad-filter");
			client.assertPingWorks();
		}
		assertThat(frameTypes(client)).as("no lobby was joined").doesNotContain("joined", "token", "roster",
				"settings", "prepare");
		client.send("create", "nickname", "Tester", "songCount", 5);
		assertThat(client.await("prepare").path("index").intValue()).isEqualTo(0);
	}

	@Test
	void soloCreateChecksTheSongCountBeforeTheFilter() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 4, "types", List.of());
		client.awaitError("bad-song-count");
		client.send("create", "nickname", "Tester", "songCount", 31, "yearFrom", 1949);
		client.awaitError("bad-song-count");
		client.assertPingWorks();
	}

	@Test
	void badFilterSoloCreatesLeaveTheLobbyCountUnchanged() throws Exception {
		// More bad solo creates than the server allows lobbies: if any of them made (or counted) a
		// lobby, the creates below would get server-full (criterion 3b, review M1).
		TestClient client = connect();
		for (int i = 0; i < GameSocketHandler.MAX_LOBBIES + 5; i++) {
			client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("IN"));
			client.awaitError("bad-filter");
		}
		createLobby(client);
		TestClient other = connect();
		other.send("create", "nickname", "Other", "songCount", 5, "types", List.of("OP"));
		assertThat(other.await("prepare").path("index").intValue()).isEqualTo(0);
	}

	// ---- bad-filter: start ----

	@Test
	void startWithABadFilterIsBadFilterToTheSenderOnlyAndNothingStarts() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		String code = createLobby(host);
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);

		for (Object[] bad : BAD_FILTERS) {
			host.send("start", concat(new Object[] {"songCount", 5}, bad));
			JsonNode error = host.await("error");
			assertThat(error.path("code").asString(null)).as("host " + describe(bad)).isEqualTo("bad-filter");
			// Checked in the handler before the lobby's not-host.
			guest.send("start", concat(new Object[] {"songCount", 5}, bad));
			error = guest.await("error");
			assertThat(error.path("code").asString(null)).as("guest " + describe(bad)).isEqualTo("bad-filter");
		}
		host.assertPingWorks();
		guest.assertPingWorks();
		for (TestClient client : List.of(host, guest)) {
			List<JsonNode> errors = frames(client).stream().filter(n -> "error".equals(n.path("type").asString(null)))
					.toList();
			assertThat(errors).as("each sender got exactly its own errors").hasSize(BAD_FILTERS.size());
			assertThat(frameTypes(client)).doesNotContain("settings", "prepare");
		}

		// The lobby is untouched: a valid start still works.
		host.send("start", "songCount", 5, "types", List.of("ED"));
		for (TestClient client : List.of(host, guest)) {
			client.await("settings");
			assertThat(client.await("prepare").path("index").intValue()).isEqualTo(0);
		}
		// During the game, bad-filter still comes before in-game.
		host.send("start", "songCount", 5, "types", List.of());
		host.awaitError("bad-filter");
		host.send("start", "songCount", 5, "types", List.of("OP"));
		host.awaitError("in-game");
	}

	@Test
	void startWithABadFilterWithoutARoomIsBadFilterBeforeNoRoom() throws Exception {
		TestClient client = connect();
		for (Object[] bad : BAD_FILTERS) {
			client.send("start", concat(new Object[] {"songCount", 5}, bad));
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as(describe(bad)).isEqualTo("bad-filter");
		}
		client.send("start", "songCount", 5, "types", List.of("OP"), "yearFrom", 1990, "yearTo", 2005);
		client.awaitError("no-room");
	}

	// ---- a valid lobby filter, with a late joiner ----

	@Test
	void aLobbyFilterReachesEveryMemberBeforePrepareAndEveryRevealMatches() throws Exception {
		// OP of 1994..2005: entries 4, 6, 8, 10, 12, 14.
		int from = 1994;
		int to = 2005;
		JsonNode expected = settings("types", List.of("OP"), "yearFrom", from, "yearTo", to);

		TestClient host = connect();
		TestClient guest = connect();
		String code = createLobby(host);
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);

		host.send("start", "songCount", 5, "types", List.of("OP"), "yearFrom", from, "yearTo", to);
		JsonNode firstPrepare = null;
		for (TestClient client : List.of(host, guest)) {
			assertThat(client.await("settings")).isEqualTo(expected);
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(0);
			List<String> types = frameTypes(client);
			assertThat(firstIndexOf(types, "settings")).isLessThan(firstIndexOf(types, "prepare"));
			assertThat(types.stream().filter("settings"::equals).count()).isEqualTo(1);
			firstPrepare = prepare;
		}

		// A late joiner during the game gets settings right after its snapshot.
		TestClient late = connect();
		late.send("join", "code", code, "nickname", "Late");
		late.await("joined");
		late.await("snapshot");
		JsonNode lateSettings = late.await("settings");
		assertThat(lateSettings).isEqualTo(expected);
		List<String> lateTypes = frameTypes(late);
		int snapshotAt = firstIndexOf(lateTypes, "snapshot");
		assertThat(lateTypes.get(snapshotAt + 1)).as("frame after the snapshot in " + lateTypes)
				.isEqualTo("settings");
		for (TestClient client : List.of(host, guest, late)) {
			awaitRoster(client, 3);
		}

		JsonNode prepare = firstPrepare;
		for (int k = 0; k < 5; k++) {
			List<TestClient> players = k == 0 ? List.of(host, guest) : List.of(host, guest, late);
			if (k > 0) {
				for (TestClient client : players) {
					JsonNode p = client.await("prepare");
					assertThat(p.path("index").intValue()).isEqualTo(k);
					if (client == host) {
						prepare = p;
					}
				}
			}
			Theme theme = themeOf(prepare);
			Anime anime = catalog.anime(theme.animeId()).orElseThrow();
			assertThat(theme.type()).as("song " + k).isEqualTo("OP");
			assertThat(anime.year()).as("song " + k).isNotNull().isBetween(from, to);
			for (TestClient client : players) {
				client.send("ready", "index", k);
			}
			assertThat(host.await("start").path("index").intValue()).isEqualTo(k);
			host.send("skipVote", "index", k);
			guest.send("skipVote", "index", k);
			for (TestClient client : List.of(host, guest, late)) {
				JsonNode reveal = client.await("reveal");
				assertThat(reveal.path("index").intValue()).isEqualTo(k);
				assertThat(reveal.path("themeType").asString(null)).isEqualTo("OP");
				assertThat(reveal.path("reveal").asString(null)).isEqualTo(anime.reveal());
			}
		}
		for (TestClient client : List.of(host, guest, late)) {
			client.await("gameOver");
			assertThat(frameTypes(client).stream().filter("settings"::equals).count())
					.as("settings once per player").isEqualTo(1);
		}
	}

	// ---- solo with a filter ----

	@Test
	void soloWithOpOnlyGetsSettingsThenPrepare() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("OP"));
		JsonNode settings = client.await("settings");
		assertThat(settings).isEqualTo(settings("types", List.of("OP")));
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
		List<String> types = frameTypes(client);
		assertThat(types.get(firstIndexOf(types, "settings") + 1)).as("frame after settings in " + types)
				.isEqualTo("prepare");
		assertThat(themeOf(prepare).type()).isEqualTo("OP");
	}

	@Test
	void soloWithoutAFilterGetsNoSettings() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.await("prepare");
		client.assertPingWorks();
		assertThat(frameTypes(client)).doesNotContain("settings");
	}

	@Test
	void settingsCarryNoCatalogValues() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5, "types", List.of("ED", "OP"), "yearFrom", 1990,
				"yearTo", 2005);
		JsonNode settings = client.await("settings");
		assertThat(settings).isEqualTo(settings("types", List.of("OP", "ED"), "yearFrom", 1990, "yearTo", 2005));
		Set<String> keys = new LinkedHashSet<>();
		settings.properties().forEach(e -> keys.add(e.getKey()));
		assertThat(keys).containsExactly("type", "types", "yearFrom", "yearTo");
	}

}
