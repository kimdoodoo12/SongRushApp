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
import java.util.HashSet;
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
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.Normalize;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 101 (D-112) over the raw WebSocket at /ws on a real server (docs/cycles/101-game-modes.md,
// items 1, 3, 4, 5, 8 and R3, R9): the start request's optional gameMode ("first" or "ranked"; absent
// means first), validated in the handler right after answerMode (bad-game-mode, before not-host,
// in-game and no-room); settings{gameMode:"ranked"}; ranked correct{type, index, name, rank} to all,
// then the sender's own chat{name, text} to the sender only with no frame in between; the reveal's
// ranking [{name, rank, points}] and winner = rank 1. The solo create path ignores gameMode (R9).
// The data folder is written into a static @TempDir before the context loads: anime i (0-11) is id
// 1000 + i in group 9000 + i with one OP theme 5000 + i of 60 s, the one title "Rank Sock Anime <i>x"
// and a disk cover "RankSock<i>x". The current song is found from the token in prepare.audioUrl and
// the Catalog bean.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RankedSocketTest {

	private static final int ENTRIES = 12;

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final long DURATION_MS = 60000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	// Bad gameMode values, each sent on its own.
	private static final List<Object> BAD_MODES = Arrays.asList("Ranked", "", "FIRST", "first-come", "choice", 1,
			null, true, List.of("ranked"), Map.of("mode", "ranked"));

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		writeData(dataDir);
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AudioTokens tokens;

	@Autowired
	private Catalog catalog;

	@Autowired
	private CoverMap covers;

	private final List<TestClient> clients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(TestClient::close);
	}

	// ---- fixture ----

	private static String basename(int i) {
		return "RankSock" + i + "-OP1.ogg";
	}

	private static String coverId(int i) {
		return "RankSock" + i + "x";
	}

	private static void writeData(Path dir) {
		List<Object> anime = new ArrayList<>();
		List<Object> themes = new ArrayList<>();
		List<Object> groups = new ArrayList<>();
		List<Object> drawSet = new ArrayList<>();
		Map<String, Object> index = new LinkedHashMap<>();
		List<String> coverEntries = new ArrayList<>();
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			Path coverCache = Files.createDirectories(dir.resolve("cover-cache"));
			for (int i = 0; i < ENTRIES; i++) {
				Map<String, List<String>> titles = new LinkedHashMap<>();
				titles.put("en", List.of("Rank Sock Anime " + i + "x"));
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 500000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Rank Sock Reveal " + i);
				anime.add(a);

				LinkedHashSet<String> accepted = new LinkedHashSet<>();
				titles.values().forEach(list -> list.forEach(t -> accepted.add(Normalize.normalize(t))));
				groups.add(Map.of("id", GROUP_BASE + i, "accepted", List.copyOf(accepted)));

				int size = 100 + i;
				Map<String, Object> t = new LinkedHashMap<>();
				t.put("id", THEME_BASE + i);
				t.put("animeId", ANIME_BASE + i);
				t.put("type", "OP");
				t.put("slug", "OP1");
				t.put("song", "Rank Sock Song " + i);
				t.put("artists", List.of("Rank Sock Singer " + i));
				t.put("audio", Map.of("basename", basename(i), "size", size));
				themes.add(t);
				drawSet.add(THEME_BASE + i);
				index.put(Integer.toString(THEME_BASE + i),
						Map.of("basename", basename(i), "durationMs", DURATION_MS, "size", size));
				Files.write(cache.resolve(basename(i)), WsFixture.audioBytes(THEME_BASE + i, size));

				Files.write(coverCache.resolve(coverId(i) + ".jpg"), new byte[9]);
				coverEntries.add("\"%d\": {\"id\": \"%s\", \"file\": \"%s.jpg\", \"size\": 9}"
						.formatted(ANIME_BASE + i, coverId(i), coverId(i)));
			}
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("version", 1);
			root.put("anime", anime);
			root.put("themes", themes);
			root.put("groups", groups);
			root.put("drawSet", drawSet);
			Files.writeString(dir.resolve("catalog.json"), MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
			Files.writeString(cache.resolve("index.json"), MAPPER.writeValueAsString(index), StandardCharsets.UTF_8);
			Files.writeString(coverCache.resolve("index.json"),
					"{\"version\": 1, \"covers\": {" + String.join(", ", coverEntries) + "}}", StandardCharsets.UTF_8);
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

	// Host first, then guests, in one lobby, all seeing everyone.
	private List<TestClient> inALobby(String... names) throws Exception {
		List<TestClient> all = new ArrayList<>();
		TestClient host = connect();
		host.send("create", "nickname", names[0]);
		String code = host.await("joined").path("code").asString(null);
		all.add(host);
		for (int n = 1; n < names.length; n++) {
			TestClient guest = connect();
			guest.send("join", "code", code, "nickname", names[n]);
			guest.await("joined");
			all.add(guest);
		}
		for (TestClient client : all) {
			awaitRoster(client, names.length);
		}
		return all;
	}

	private static Set<String> keys(JsonNode node) {
		Set<String> keys = new LinkedHashSet<>();
		node.properties().forEach(e -> keys.add(e.getKey()));
		return keys;
	}

	private Theme themeOf(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		return catalog.theme(themeId).orElseThrow();
	}

	private String titleOf(JsonNode prepare) {
		return catalog.anime(themeOf(prepare).animeId()).orElseThrow().titles().get("en").get(0);
	}

	private int correctPosition(JsonNode prepare) {
		String coverUrl = "/cover/" + covers.idFor(themeOf(prepare).animeId()).orElseThrow();
		List<String> urls = new ArrayList<>();
		for (JsonNode choice : prepare.path("choices")) {
			urls.add(choice.path("coverUrl").asString(null));
		}
		int pos = urls.indexOf(coverUrl);
		assertThat(pos).as("the song's cover is a choice").isGreaterThanOrEqualTo(0);
		return pos;
	}

	private static void waitUntilAudible(JsonNode start) throws InterruptedException {
		long startAt = start.path("startAt").longValue();
		long left = startAt - System.currentTimeMillis();
		if (left > 0) {
			Thread.sleep(left + 200);
		}
	}

	// Everyone readies song 0 and waits until it is audible; returns the host's prepare.
	private static JsonNode toAudible(List<TestClient> all) throws Exception {
		JsonNode prepare = null;
		for (TestClient client : all) {
			JsonNode p = client.await("prepare");
			if (prepare == null) {
				prepare = p;
			}
		}
		for (TestClient client : all) {
			client.send("ready", "index", 0);
		}
		JsonNode start = null;
		for (TestClient client : all) {
			start = client.await("start");
		}
		waitUntilAudible(start);
		return prepare;
	}

	private static Map<String, Object> startFields(Object gameMode) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "start");
		m.put("songCount", 5);
		m.put("gameMode", gameMode);
		return m;
	}

	private static void assertRanking(JsonNode reveal, Object... nameRankPoints) {
		JsonNode ranking = reveal.path("ranking");
		assertThat(ranking.isArray()).as("ranking in " + reveal).isTrue();
		assertThat(ranking.size()).isEqualTo(nameRankPoints.length / 3);
		for (int i = 0; i < ranking.size(); i++) {
			JsonNode row = ranking.get(i);
			assertThat(keys(row)).containsExactlyInAnyOrder("name", "rank", "points");
			assertThat(row.path("name").asString(null)).isEqualTo(nameRankPoints[3 * i]);
			assertThat(row.path("rank").intValue()).isEqualTo(nameRankPoints[3 * i + 1]);
			assertThat(row.path("points").intValue()).isEqualTo(nameRankPoints[3 * i + 2]);
		}
	}

	// ---- bad-game-mode ----

	@Test
	void aBadGameModeIsRefusedBeforeNotHostAndNothingStarts() throws Exception {
		List<TestClient> two = inALobby("Host", "Guest");
		TestClient host = two.get(0);
		for (Object bad : BAD_MODES) {
			for (TestClient client : two) {
				client.sendRaw(MAPPER.writeValueAsString(startFields(bad)));
				JsonNode error = client.await("error");
				assertThat(error.path("code").asString(null)).as((client == host ? "host " : "guest ") + bad)
						.isEqualTo("bad-game-mode");
			}
		}
		for (TestClient client : two) {
			client.assertPingWorks();
			assertThat(frameTypes(client)).doesNotContain("settings", "prepare");
			long errors = frameTypes(client).stream().filter("error"::equals).count();
			assertThat(errors).as("each sender got exactly its own errors").isEqualTo(BAD_MODES.size());
		}

		// bad-filter, then bad-answer-mode, come before bad-game-mode.
		host.send("start", "songCount", 5, "tier", "impossible", "answerMode", "quiz", "gameMode", "solo");
		host.awaitError("bad-filter");
		host.send("start", "songCount", 5, "answerMode", "quiz", "gameMode", "solo");
		host.awaitError("bad-answer-mode");

		// The lobby is untouched; during a game bad-game-mode still comes before in-game.
		host.send("start", "songCount", 5, "gameMode", "ranked");
		for (TestClient client : two) {
			client.await("settings");
			client.await("prepare");
		}
		host.send("start", "songCount", 5, "gameMode", "Ranked");
		host.awaitError("bad-game-mode");
		host.send("start", "songCount", 5, "gameMode", "first");
		host.awaitError("in-game");
	}

	@Test
	void aBadGameModeWithoutARoomIsRefusedBeforeNoRoom() throws Exception {
		TestClient client = connect();
		client.send("start", "songCount", 5, "gameMode", "team");
		client.awaitError("bad-game-mode");
		client.send("start", "songCount", 5, "gameMode", "ranked");
		client.awaitError("no-room");
	}

	// ---- first come ----

	@Test
	void anExplicitFirstStartIsTodaysGame() throws Exception {
		List<TestClient> two = inALobby("Host", "Guest");
		TestClient host = two.get(0);
		TestClient guest = two.get(1);
		host.send("start", "songCount", 5, "gameMode", "first");
		JsonNode prepare = toAudible(two);
		String title = titleOf(prepare);
		guest.send("chat", "text", title);
		for (TestClient client : two) {
			JsonNode correct = client.await("correct");
			assertThat(keys(correct)).containsExactly("type", "index", "name");
			assertThat(correct.path("name").asString(null)).isEqualTo("Guest");
			JsonNode chat = client.await("chat");
			assertThat(chat.path("text").asString(null)).as("D-094: the winning text goes to all").isEqualTo(title);
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Guest");
			assertThat(reveal.has("ranking")).isFalse();
			assertThat(frameTypes(client)).doesNotContain("settings");
		}
		host.assertPingWorks();
	}

	// ---- ranked chat ----

	@Test
	void aRankedChatGameFromSettingsToTheRanking() throws Exception {
		List<TestClient> three = inALobby("Host", "Guest", "Third");
		TestClient host = three.get(0);
		TestClient guest = three.get(1);
		TestClient third = three.get(2);
		host.send("start", "songCount", 5, "gameMode", "ranked");
		for (TestClient client : three) {
			JsonNode settings = client.await("settings");
			assertThat(keys(settings)).containsExactlyInAnyOrder("type", "gameMode");
			assertThat(settings.path("gameMode").asString(null)).isEqualTo("ranked");
		}
		JsonNode prepare = toAudible(three);
		assertThat(keys(prepare)).containsExactlyInAnyOrder("type", "index", "total", "audioUrl");
		String title = titleOf(prepare);

		List<TestClient> order = List.of(guest, host, third);
		List<String> names = List.of("Guest", "Host", "Third");
		for (int r = 0; r < 3; r++) {
			TestClient sender = order.get(r);
			sender.send("chat", "text", title);
			for (TestClient client : three) {
				JsonNode correct = client.await("correct");
				assertThat(keys(correct)).containsExactly("type", "index", "name", "rank");
				assertThat(correct.path("index").intValue()).isEqualTo(0);
				assertThat(correct.path("name").asString(null)).isEqualTo(names.get(r));
				assertThat(correct.path("rank").intValue()).isEqualTo(r + 1);
			}
			JsonNode echo = sender.await("chat");
			assertThat(echo.path("name").asString(null)).isEqualTo(names.get(r));
			assertThat(echo.path("text").asString(null)).isEqualTo(title);
		}
		for (TestClient client : three) {
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Guest");
			assertRanking(reveal, "Guest", 1, 3, "Host", 2, 2, "Third", 3, 1);
			JsonNode scores = client.await("scores");
			Map<String, Integer> byName = new LinkedHashMap<>();
			for (JsonNode row : scores.path("scores")) {
				byName.put(row.path("name").asString(null), row.path("score").intValue());
			}
			assertThat(byName).containsEntry("Guest", 3).containsEntry("Host", 2).containsEntry("Third", 1);
		}

		for (int c = 0; c < three.size(); c++) {
			List<JsonNode> frames = frames(three.get(c));
			String me = names.get(order.indexOf(three.get(c)));
			int chats = 0;
			for (int i = 0; i < frames.size(); i++) {
				JsonNode frame = frames.get(i);
				String type = frame.path("type").asString(null);
				if ("reveal".equals(type)) {
					break;
				}
				// R3: my own correct is followed at once by my own echo.
				if ("correct".equals(type) && me.equals(frame.path("name").asString(null))) {
					JsonNode next = frames.get(i + 1);
					assertThat(next.path("type").asString(null)).as("the frame after my correct").isEqualTo("chat");
					assertThat(next.path("name").asString(null)).isEqualTo(me);
				}
				if ("chat".equals(type)) {
					chats++;
					assertThat(frame.path("name").asString(null)).as("only my own line reaches me").isEqualTo(me);
				}
				// Item 8: nothing before the reveal names the song, except my own echo.
				if (!"chat".equals(type)) {
					assertThat(frame.toString()).as(type).doesNotContain("Rank Sock");
				}
			}
			assertThat(chats).isEqualTo(1);
		}
	}

	@Test
	void concurrentCorrectChatsAreRankedByArrival() throws Exception {
		List<TestClient> three = inALobby("Host", "Guest", "Third");
		TestClient host = three.get(0);
		TestClient guest = three.get(1);
		TestClient third = three.get(2);
		host.send("start", "songCount", 5, "gameMode", "ranked");
		JsonNode prepare = toAudible(three);
		String title = titleOf(prepare);
		host.send("chat", "text", title);
		guest.send("chat", "text", title);
		List<String> firstSeen = null;
		for (TestClient client : three) {
			JsonNode a = client.await("correct");
			JsonNode b = client.await("correct");
			assertThat(a.path("rank").intValue()).isEqualTo(1);
			assertThat(b.path("rank").intValue()).isEqualTo(2);
			List<String> seen = List.of(a.path("name").asString(null), b.path("name").asString(null));
			assertThat(new HashSet<>(seen)).containsExactlyInAnyOrder("Host", "Guest");
			if (firstSeen == null) {
				firstSeen = seen;
			}
			else {
				assertThat(seen).as("every client sees the same order").isEqualTo(firstSeen);
			}
		}
		third.send("chat", "text", title);
		for (TestClient client : three) {
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo(firstSeen.get(0));
			assertRanking(reveal, firstSeen.get(0), 1, 3, firstSeen.get(1), 2, 2, "Third", 3, 1);
		}
	}

	// ---- ranked choice ----

	@Test
	void aRankedChoiceGameRanksPicksAndEndsWhenAllHavePicked() throws Exception {
		List<TestClient> three = inALobby("Host", "Guest", "Third");
		TestClient host = three.get(0);
		TestClient guest = three.get(1);
		TestClient third = three.get(2);
		host.send("start", "songCount", 5, "answerMode", "choice", "gameMode", "ranked");
		for (TestClient client : three) {
			JsonNode settings = client.await("settings");
			assertThat(keys(settings)).containsExactlyInAnyOrder("type", "answerMode", "gameMode");
			assertThat(settings.path("answerMode").asString(null)).isEqualTo("choice");
			assertThat(settings.path("gameMode").asString(null)).isEqualTo("ranked");
		}
		JsonNode prepare = toAudible(three);
		int correct = correctPosition(prepare);
		int wrong = correct == 0 ? 1 : 0;
		String title = titleOf(prepare);

		guest.send("pick", "index", 0, "choice", correct);
		for (TestClient client : three) {
			JsonNode win = client.await("correct");
			assertThat(keys(win)).containsExactly("type", "index", "name", "rank");
			assertThat(win.path("name").asString(null)).isEqualTo("Guest");
			assertThat(win.path("rank").intValue()).isEqualTo(1);
		}
		// Chat never scores in a choice room.
		host.send("chat", "text", title);
		for (TestClient client : three) {
			assertThat(client.await("chat").path("text").asString(null)).isEqualTo(title);
		}
		third.send("pick", "index", 0, "choice", wrong);
		assertThat(third.await("pick-result").path("choice").intValue()).isEqualTo(wrong);
		host.assertPingWorks();
		assertThat(frameTypes(host)).doesNotContain("reveal", "pick-result");

		host.send("pick", "index", 0, "choice", correct);
		for (TestClient client : three) {
			JsonNode win = client.await("correct");
			assertThat(win.path("name").asString(null)).isEqualTo("Host");
			assertThat(win.path("rank").intValue()).isEqualTo(2);
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Guest");
			assertThat(reveal.path("correctChoice").intValue()).isEqualTo(correct);
			assertRanking(reveal, "Guest", 1, 3, "Host", 2, 2);
		}
		for (TestClient client : three) {
			long corrects = frameTypes(client).stream().filter("correct"::equals).count();
			assertThat(corrects).isEqualTo(2);
		}
	}

	// ---- solo (R9) ----

	@Test
	void theSoloCreatePathIsAlwaysFirstComeAndIgnoresGameMode() throws Exception {
		for (Object mode : Arrays.asList("ranked", "bogus")) {
			TestClient solo = connect();
			Map<String, Object> create = new LinkedHashMap<>();
			create.put("type", "create");
			create.put("nickname", "Solo");
			create.put("songCount", 5);
			create.put("gameMode", mode);
			solo.sendRaw(MAPPER.writeValueAsString(create));
			solo.await("joined");
			JsonNode prepare = toAudible(List.of(solo));
			String title = titleOf(prepare);
			solo.send("chat", "text", title);
			JsonNode correct = solo.await("correct");
			assertThat(keys(correct)).as("mode " + mode).containsExactly("type", "index", "name");
			JsonNode reveal = solo.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Solo");
			assertThat(reveal.has("ranking")).as("mode " + mode).isFalse();
			assertThat(frameTypes(solo)).as("mode " + mode).doesNotContain("settings", "error");
			solo.close();
		}
	}

}
