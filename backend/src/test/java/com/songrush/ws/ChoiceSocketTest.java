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
import java.util.regex.Pattern;

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

// Cycle 100 (D-111) over the raw WebSocket at /ws on a real server
// (docs/cycles/100-choice-answers.md, amendments A3, A4, A8, A9, A11 and the owner's O1-O2): the
// start request's answerMode, the pick message {type:"pick", index, choice} (0-based positions), and
// the frames that answer it. The data folder is written into a static @TempDir before the context
// loads: anime i (0-11) is id 1000 + i in group 9000 + i with one OP theme 5000 + i of 60 s and a
// disk cover "ChoiceSock<i>x". The correct position is found from the token in prepare.audioUrl, the
// Catalog bean and the CoverMap bean.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChoiceSocketTest {

	private static final int ENTRIES = 12;

	private static final int ANIME_BASE = 1000;

	private static final int THEME_BASE = 5000;

	private static final int GROUP_BASE = 9000;

	private static final long DURATION_MS = 60000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final Pattern COVER_URL = Pattern.compile("^/cover/[A-Za-z0-9_-]{1,64}$");

	// Bad answerMode values, each sent on its own.
	private static final List<Object> BAD_MODES = Arrays.asList("Choice", "", "multiple", "CHAT", 1, null, true,
			List.of("choice"), Map.of("mode", "choice"));

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
		return "ChoiceSock" + i + "-OP1.ogg";
	}

	private static String coverId(int i) {
		return "ChoiceSock" + i + "x";
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
				titles.put("en", List.of("Choice Sock Anime " + i + "x"));
				Map<String, Object> a = new LinkedHashMap<>();
				a.put("id", ANIME_BASE + i);
				a.put("anilistId", 400000 + i);
				a.put("group", GROUP_BASE + i);
				a.put("titles", titles);
				a.put("reveal", "Choice Sock Reveal " + i);
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
				t.put("song", "Choice Sock Song " + i);
				t.put("artists", List.of("Choice Sock Singer " + i));
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

	// Host and guest in one lobby, both seeing two players.
	private List<TestClient> twoInALobby() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);
		return List.of(host, guest);
	}

	private static Set<String> keys(JsonNode node) {
		Set<String> keys = new LinkedHashSet<>();
		node.properties().forEach(e -> keys.add(e.getKey()));
		return keys;
	}

	private static List<String> coverUrls(JsonNode prepare) {
		assertThat(prepare.path("choices").isArray()).as("choices array in " + prepare).isTrue();
		List<String> urls = new ArrayList<>();
		for (JsonNode choice : prepare.path("choices")) {
			assertThat(keys(choice)).containsExactly("coverUrl");
			urls.add(choice.path("coverUrl").asString(null));
		}
		return urls;
	}

	private int correctPosition(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		Theme theme = catalog.theme(themeId).orElseThrow();
		String coverUrl = "/cover/" + covers.idFor(theme.animeId()).orElseThrow();
		int pos = coverUrls(prepare).indexOf(coverUrl);
		assertThat(pos).as("the song's cover is a choice").isGreaterThanOrEqualTo(0);
		return pos;
	}

	// Waits on the wall clock until the server's startAt has passed (same machine clock).
	private static void waitUntilAudible(JsonNode start) throws InterruptedException {
		long startAt = start.path("startAt").longValue();
		long left = startAt - System.currentTimeMillis();
		if (left > 0) {
			Thread.sleep(left + 200);
		}
	}

	private static Map<String, Object> startFields(Object mode) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "start");
		m.put("songCount", 5);
		m.put("answerMode", mode);
		return m;
	}

	// ---- bad-answer-mode ----

	@Test
	void aBadAnswerModeIsRefusedBeforeNotHostAndNothingStarts() throws Exception {
		List<TestClient> two = twoInALobby();
		TestClient host = two.get(0);
		TestClient guest = two.get(1);
		for (Object bad : BAD_MODES) {
			for (TestClient client : two) {
				client.sendRaw(MAPPER.writeValueAsString(startFields(bad)));
				JsonNode error = client.await("error");
				assertThat(error.path("code").asString(null)).as((client == host ? "host " : "guest ") + bad)
						.isEqualTo("bad-answer-mode");
			}
		}
		host.assertPingWorks();
		guest.assertPingWorks();
		for (TestClient client : two) {
			assertThat(frameTypes(client)).doesNotContain("settings", "prepare");
			long errors = frameTypes(client).stream().filter("error"::equals).count();
			assertThat(errors).as("each sender got exactly its own errors").isEqualTo(BAD_MODES.size());
		}
		// The lobby is untouched.
		host.send("start", "songCount", 5, "answerMode", "choice");
		for (TestClient client : two) {
			client.await("settings");
			client.await("prepare");
		}
		// During a game, bad-answer-mode still comes before in-game.
		host.send("start", "songCount", 5, "answerMode", "Choice");
		host.awaitError("bad-answer-mode");
		host.send("start", "songCount", 5, "answerMode", "chat");
		host.awaitError("in-game");
	}

	@Test
	void aBadAnswerModeWithoutARoomIsRefusedBeforeNoRoom() throws Exception {
		TestClient client = connect();
		client.send("start", "songCount", 5, "answerMode", "quiz");
		client.awaitError("bad-answer-mode");
		client.send("start", "songCount", 5, "answerMode", "choice");
		client.awaitError("no-room");
	}

	// ---- valid modes ----

	@Test
	void aChatStartIsTodaysGame() throws Exception {
		List<TestClient> two = twoInALobby();
		two.get(0).send("start", "songCount", 5, "answerMode", "chat");
		for (TestClient client : two) {
			JsonNode prepare = client.await("prepare");
			assertThat(keys(prepare)).containsExactlyInAnyOrder("type", "index", "total", "audioUrl");
			assertThat(frameTypes(client)).doesNotContain("settings");
		}
	}

	// ---- a choice game ----

	@Test
	void aChoiceGameFromSettingsToTheReveal() throws Exception {
		List<TestClient> two = twoInALobby();
		TestClient host = two.get(0);
		TestClient guest = two.get(1);
		host.send("start", "songCount", 5, "answerMode", "choice");
		JsonNode hostPrepare = null;
		List<String> urls = null;
		for (TestClient client : two) {
			JsonNode settings = client.await("settings");
			assertThat(keys(settings)).containsExactly("type", "answerMode");
			assertThat(settings.path("answerMode").asString(null)).isEqualTo("choice");
			JsonNode prepare = client.await("prepare");
			assertThat(keys(prepare)).containsExactlyInAnyOrder("type", "index", "total", "audioUrl", "choices");
			List<String> mine = coverUrls(prepare);
			assertThat(mine).hasSize(6).allMatch(u -> COVER_URL.matcher(u).matches()).doesNotHaveDuplicates();
			if (urls == null) {
				urls = mine;
				hostPrepare = prepare;
			}
			else {
				assertThat(mine).as("everyone gets the same choices").isEqualTo(urls);
			}
		}
		int correct = correctPosition(hostPrepare);
		int wrong = correct == 0 ? 1 : 0;
		for (TestClient client : two) {
			client.send("ready", "index", 0);
		}
		JsonNode start = host.await("start");
		guest.await("start");
		assertThat(keys(start)).containsExactlyInAnyOrder("type", "index", "startAt", "deadlineAt", "serverTime");

		// Before startAt.
		host.send("pick", "index", 0, "choice", correct);
		JsonNode early = host.await("error");
		assertThat(keys(early)).containsExactly("type", "code", "for");
		assertThat(early.path("code").asString(null)).isEqualTo("too-early");
		assertThat(early.path("for").asString(null)).isEqualTo("pick");

		waitUntilAudible(start);
		guest.send("pick", "index", 0, "choice", wrong);
		JsonNode result = guest.await("pick-result");
		assertThat(keys(result)).containsExactly("type", "index", "choice");
		assertThat(result.path("index").intValue()).isEqualTo(0);
		assertThat(result.path("choice").intValue()).isEqualTo(wrong);
		guest.send("pick", "index", 0, "choice", correct);
		JsonNode again = guest.await("error");
		assertThat(again.path("code").asString(null)).isEqualTo("already-picked");
		assertThat(again.path("for").asString(null)).isEqualTo("pick");

		host.send("pick", "index", 0, "choice", correct);
		for (TestClient client : two) {
			JsonNode win = client.await("correct");
			assertThat(win.path("index").intValue()).isEqualTo(0);
			assertThat(win.path("name").asString(null)).isEqualTo("Host");
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Host");
			assertThat(reveal.path("correctChoice").intValue()).isEqualTo(correct);
			assertThat(reveal.path("coverUrl").asString(null)).isEqualTo(urls.get(correct));
		}
		host.assertPingWorks();
		assertThat(frameTypes(host)).as("the guest's pick-result reaches the guest only").doesNotContain("pick-result");

		// Nothing before a reveal marks the correct choice.
		for (TestClient client : two) {
			for (JsonNode frame : frames(client)) {
				if (!"reveal".equals(frame.path("type").asString(null))) {
					assertThat(frame.toString()).doesNotContain("correctChoice").doesNotContain("Choice Sock Anime")
							.doesNotContain("Choice Sock Reveal");
				}
			}
		}
	}

	// O1: the exact title by chat wins in a choice room too.
	@Test
	void anExactTitleByChatWinsInAChoiceRoom() throws Exception {
		List<TestClient> two = twoInALobby();
		TestClient host = two.get(0);
		TestClient guest = two.get(1);
		host.send("start", "songCount", 5, "answerMode", "choice");
		JsonNode prepare = host.await("prepare");
		guest.await("prepare");
		int correct = correctPosition(prepare);
		String url = prepare.path("audioUrl").asString(null);
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		String title = catalog.anime(catalog.theme(themeId).orElseThrow().animeId()).orElseThrow().titles().get("en")
				.get(0);
		for (TestClient client : two) {
			client.send("ready", "index", 0);
		}
		JsonNode start = host.await("start");
		waitUntilAudible(start);
		guest.send("pick", "index", 0, "choice", correct == 0 ? 1 : 0);
		guest.await("pick-result");
		guest.send("chat", "text", title);
		for (TestClient client : two) {
			assertThat(client.await("correct").path("name").asString(null)).isEqualTo("Guest");
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("winner").asString(null)).isEqualTo("Guest");
			assertThat(reveal.path("correctChoice").intValue()).isEqualTo(correct);
		}
	}

	@Test
	void concurrentCorrectPicksHaveOneWinner() throws Exception {
		List<TestClient> two = twoInALobby();
		TestClient host = two.get(0);
		TestClient guest = two.get(1);
		host.send("start", "songCount", 5, "answerMode", "choice");
		JsonNode prepare = host.await("prepare");
		guest.await("prepare");
		int correct = correctPosition(prepare);
		for (TestClient client : two) {
			client.send("ready", "index", 0);
		}
		JsonNode start = host.await("start");
		waitUntilAudible(start);
		host.send("pick", "index", 0, "choice", correct);
		guest.send("pick", "index", 0, "choice", correct);
		String winner = null;
		for (TestClient client : two) {
			JsonNode reveal = client.await("reveal");
			winner = reveal.path("winner").asString(null);
			assertThat(winner).isIn("Host", "Guest");
			JsonNode scores = client.await("scores");
			int total = 0;
			for (JsonNode row : scores.path("scores")) {
				total += row.path("score").intValue();
			}
			assertThat(total).isEqualTo(1);
		}
		for (TestClient client : two) {
			client.assertPingWorks();
			assertThat(frameTypes(client).stream().filter("correct"::equals).count()).isEqualTo(1);
		}
	}

	// ---- the pick message itself ----

	@Test
	void aMalformedPickIsBadMessageAndAPickWithoutARoomIsNoRoom() throws Exception {
		TestClient client = connect();
		List<Map<String, Object>> bad = new ArrayList<>();
		bad.add(Map.of("type", "pick", "choice", 0));
		bad.add(Map.of("type", "pick", "index", 0));
		bad.add(Map.of("type", "pick", "index", "0", "choice", 0));
		bad.add(Map.of("type", "pick", "index", 0, "choice", "1"));
		bad.add(Map.of("type", "pick", "index", 0, "choice", 1.5));
		for (Map<String, Object> m : bad) {
			client.sendRaw(MAPPER.writeValueAsString(m));
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as(m.toString()).isEqualTo("bad-message");
		}
		client.send("pick", "index", 0, "choice", 0);
		client.awaitError("no-room");
	}

}
