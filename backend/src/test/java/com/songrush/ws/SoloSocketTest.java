package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

// Cycle 037 (B-007) criteria 1-4: a solo game over the raw WebSocket at /ws on a real server, with
// songrush.data-dir pointing at a temp folder written before the context loads (as AudioEndpointTest
// does). 8 drawable songs of 3000 ms each. The current song is found by resolving the token in
// prepare.audioUrl with the AudioTokens bean and reading the Catalog bean
// (docs/cycles/037-solo-websocket.md). "Song 2" is index 1, the second song.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoloSocketTest {

	private static final int SONGS = 8;

	private static final int UNANSWERED = 1;

	// Title key used to answer song k (cycled), so non-ASCII guesses cross the socket too.
	private static final List<String> ANSWER_KEYS = List.of("en", "ko", "ja", "romaji");

	// Margin after startAt before a guess, so the guess cannot be early.
	private static final long AFTER_START_MS = 300;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		WsFixture.write(dataDir, SONGS);
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

	private TestClient connect() throws Exception {
		TestClient client = TestClient.connect(port);
		clients.add(client);
		return client;
	}

	private int themeOf(JsonNode prepare) {
		String url = prepare.path("audioUrl").asString(null);
		assertThat(url).startsWith("/audio/");
		Integer themeId = tokens.resolve(url.substring("/audio/".length())).orElse(null);
		assertThat(themeId).as("token in audioUrl resolves").isNotNull();
		return themeId;
	}

	private Anime animeOf(int themeId) {
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private void assertDownloads(JsonNode prepare, int themeId) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
				+ prepare.path("audioUrl").asString(null)))
				.timeout(Duration.ofSeconds(15))
				.GET()
				.build();
		HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		int i = WsFixture.indexOfTheme(themeId);
		assertThat(response.body()).isEqualTo(WsFixture.audioBytes(themeId, WsFixture.size(i)));
	}

	// The song's audio basename and every title; none may appear in a frame before its reveal.
	private List<String> secrets(int themeId) {
		List<String> secrets = new ArrayList<>();
		secrets.add(WsFixture.basename(WsFixture.indexOfTheme(themeId)));
		animeOf(themeId).titles().values().forEach(secrets::addAll);
		return secrets;
	}

	private static void collectStrings(JsonNode node, List<String> out) {
		if (node == null) {
			return;
		}
		if (node.isString()) {
			out.add(node.stringValue());
			return;
		}
		for (JsonNode child : node) {
			collectStrings(child, out);
		}
	}

	// Checks both the raw text and the decoded string values (the server may escape non-ASCII).
	private static void assertNoLeak(List<String> frames, List<String> secrets, int index) {
		for (String frame : frames) {
			List<String> strings = new ArrayList<>();
			strings.add(frame);
			try {
				collectStrings(TestClient.MAPPER.readTree(frame), strings);
			}
			catch (JacksonException e) {
				// Not JSON: the raw text is still checked.
			}
			for (String s : strings) {
				for (String secret : secrets) {
					assertThat(s).as("frame before reveal of song %d", index).doesNotContain(secret);
				}
			}
		}
	}

	private static boolean isRevealOf(String frame, int index) {
		JsonNode node;
		try {
			node = TestClient.MAPPER.readTree(frame);
		}
		catch (JacksonException e) {
			return false;
		}
		return node != null && node.isObject() && "reveal".equals(node.path("type").asString(null))
				&& node.path("index").intValue() == index;
	}

	private static void sleepUntil(long epochMillis) throws InterruptedException {
		long left = epochMillis - System.currentTimeMillis();
		if (left > 0) {
			Thread.sleep(left);
		}
	}

	// Criterion 1
	@Test
	void soloGameOfFiveSongs() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		Set<Integer> played = new HashSet<>();
		for (int k = 0; k < 5; k++) {
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(k);
			assertThat(prepare.path("total").intValue()).isEqualTo(5);
			int themeId = themeOf(prepare);
			assertThat(played.add(themeId)).as("song %d is not a repeat", k).isTrue();
			assertDownloads(prepare, themeId);

			client.send("ready", "index", k);
			JsonNode start = client.await("start");
			assertThat(start.path("index").intValue()).isEqualTo(k);
			long startAt = start.path("startAt").longValue();
			long deadlineAt = start.path("deadlineAt").longValue();
			assertThat(deadlineAt - startAt).isEqualTo(WsFixture.DURATION_MS);

			JsonNode reveal;
			if (k == UNANSWERED) {
				reveal = client.await("reveal");
				assertThat(System.currentTimeMillis()).as("unanswered song ends at its deadline")
						.isGreaterThanOrEqualTo(deadlineAt - 100);
				assertThat(reveal.path("winner").isNull()).as("winner of the unanswered song is null").isTrue();
			}
			else {
				sleepUntil(startAt + AFTER_START_MS);
				String key = ANSWER_KEYS.get(k % ANSWER_KEYS.size());
				client.send("chat", "text", animeOf(themeId).titles().get(key).get(0));
				JsonNode correct = client.await("correct");
				assertThat(correct.path("index").intValue()).isEqualTo(k);
				assertThat(correct.path("name").asString(null)).isEqualTo("Tester");
				reveal = client.await("reveal");
				assertThat(reveal.path("winner").asString(null)).isEqualTo("Tester");
			}
			assertThat(reveal.path("index").intValue()).isEqualTo(k);

			// Every frame up to (not including) this reveal must not name this song.
			List<String> frames = client.received();
			int revealAt = frames.size() - 1;
			while (revealAt >= 0 && !isRevealOf(frames.get(revealAt), k)) {
				revealAt--;
			}
			assertThat(revealAt).isGreaterThanOrEqualTo(0);
			assertNoLeak(frames.subList(0, revealAt), secrets(themeId), k);

			JsonNode scores = client.await("scores");
			JsonNode row = scores.path("scores").path(0);
			assertThat(row.path("name").asString(null)).isEqualTo("Tester");
			int expected = k < UNANSWERED ? k + 1 : k;
			assertThat(row.path("score").intValue()).isEqualTo(expected);
		}
		JsonNode gameOver = client.await("gameOver");
		JsonNode ranking = gameOver.path("ranking");
		assertThat(ranking.size()).isEqualTo(1);
		assertThat(ranking.path(0).path("rank").intValue()).isEqualTo(1);
		assertThat(ranking.path(0).path("name").asString(null)).isEqualTo("Tester");
		assertThat(ranking.path(0).path("score").intValue()).isEqualTo(4);
		client.assertPingWorks();
	}

	// Criterion 2: works before create; serverTime between the test's clock readings
	@Test
	void pingGetsPongBeforeCreate() throws Exception {
		TestClient client = connect();
		long before = System.currentTimeMillis();
		client.send("ping", "id", 7);
		JsonNode pong = client.await("pong");
		long after = System.currentTimeMillis();
		assertThat(pong.path("id").isIntegralNumber()).isTrue();
		assertThat(pong.path("id").intValue()).isEqualTo(7);
		assertThat(pong.path("serverTime").isIntegralNumber()).isTrue();
		assertThat(pong.path("serverTime").longValue()).isBetween(before, after);
	}

	// Criterion 2: id may be absent
	@Test
	void pingWithoutIdGetsPong() throws Exception {
		TestClient client = connect();
		long before = System.currentTimeMillis();
		client.send("ping");
		JsonNode pong = client.await("pong");
		assertThat(pong.path("serverTime").longValue()).isBetween(before, System.currentTimeMillis());
		client.assertPingWorks();
	}

	// Criterion 2: a ping works with a room too
	@Test
	void pingWorksInARoom() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.await("prepare");
		client.assertPingWorks();
	}

	// Criterion 3
	@Test
	void chatOrReadyBeforeCreateIsNoRoom() throws Exception {
		TestClient client = connect();
		client.send("chat", "text", "hello");
		client.awaitError("no-room");
		client.assertPingWorks();
		client.send("ready", "index", 0);
		client.awaitError("no-room");
		client.assertPingWorks();
	}

	// Criterion 3
	@Test
	void secondCreateIsAlreadyInRoom() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.await("prepare");
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.awaitError("already-in-room");
		client.assertPingWorks();
	}

	// Criterion 3
	@Test
	void badNicknames() throws Exception {
		TestClient client = connect();
		for (String nickname : List.of("", "   ", "abcdefghijklmnopq")) {
			client.send("create", "nickname", nickname, "songCount", 5);
			client.awaitError("bad-nickname");
			client.assertPingWorks();
		}
		// 16 characters after trimming is allowed.
		client.send("create", "nickname", "  abcdefghijklmnop  ", "songCount", 5);
		client.await("prepare");
	}

	// Criterion 3: the rejected room was discarded, so a following create works
	@Test
	void badSongCountThenCreateWorks() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 4);
		client.awaitError("bad-song-count");
		client.assertPingWorks();
		client.send("create", "nickname", "Tester", "songCount", 31);
		client.awaitError("bad-song-count");
		client.assertPingWorks();
		client.send("create", "nickname", "Tester", "songCount", 5);
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
		assertThat(prepare.path("total").intValue()).isEqualTo(5);
	}

	// Criterion 3
	@Test
	void malformedFramesAreBadMessage() throws Exception {
		TestClient client = connect();
		List<String> bad = List.of(
				"not json",
				"[1,2]",
				"{\"nickname\":\"Tester\",\"songCount\":5}",
				"{\"type\":5}",
				"{\"type\":\"dance\"}",
				"{\"type\":\"create\",\"nickname\":\"Tester\",\"songCount\":\"5\"}",
				"{\"type\":\"create\",\"nickname\":\"Tester\",\"songCount\":5.5}",
				"{\"type\":\"create\",\"nickname\":5,\"songCount\":5}");
		for (String frame : bad) {
			client.sendRaw(frame);
			JsonNode error = client.await("error");
			assertThat(error.path("code").asString(null)).as("reply to %s", frame).isEqualTo("bad-message");
			client.assertPingWorks();
		}
	}

	// Criterion 3: wrong field types inside a room are bad-message too
	@Test
	void wrongFieldTypesInARoomAreBadMessage() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.await("prepare");
		client.sendRaw("{\"type\":\"ready\",\"index\":\"0\"}");
		client.awaitError("bad-message");
		client.assertPingWorks();
		client.sendRaw("{\"type\":\"chat\",\"text\":5}");
		client.awaitError("bad-message");
		client.assertPingWorks();
	}

	// Criterion 3: more than 4096 UTF-8 bytes is too-large and not parsed
	@Test
	void largeFrameIsTooLarge() throws Exception {
		TestClient client = connect();
		String head = "{\"type\":\"ping\",\"id\":1,\"pad\":\"";
		String tail = "\"}";
		String frame = head + "a".repeat(5000 - head.length() - tail.length()) + tail;
		assertThat(frame.getBytes(StandardCharsets.UTF_8)).hasSize(5000);
		client.sendRaw(frame);
		client.awaitError("too-large");
		client.assertPingWorks();
	}

	// Criterion 4: leave while PLAYING stops the room; chat is no-room; a new create works
	@Test
	void leaveWhilePlayingDiscardsTheRoom() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "Tester", "songCount", 5);
		client.await("prepare");
		client.send("ready", "index", 0);
		JsonNode start = client.await("start");
		long deadlineAt = start.path("deadlineAt").longValue();
		client.send("leave");
		client.assertNone(Set.of("reveal", "prepare", "scores", "gameOver"), deadlineAt + 1000);
		assertThat(client.isOpen()).isTrue();
		client.send("chat", "text", "hello");
		client.awaitError("no-room");
		client.send("create", "nickname", "Tester", "songCount", 5);
		JsonNode prepare = client.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(0);
	}

	// Criterion 4: a socket closed mid-song does not hurt the server; a new connection can play
	@Test
	void closeMidSongThenNewConnectionPlays() throws Exception {
		TestClient first = connect();
		first.send("create", "nickname", "Tester", "songCount", 5);
		first.await("prepare");
		first.send("ready", "index", 0);
		JsonNode firstStart = first.await("start");
		first.close();
		sleepUntil(firstStart.path("deadlineAt").longValue() + 500);

		TestClient second = connect();
		second.assertPingWorks();
		second.send("create", "nickname", "Tester", "songCount", 5);
		JsonNode prepare = second.await("prepare");
		int themeId = themeOf(prepare);
		assertDownloads(prepare, themeId);
		second.send("ready", "index", 0);
		JsonNode start = second.await("start");
		sleepUntil(start.path("startAt").longValue() + AFTER_START_MS);
		second.send("chat", "text", animeOf(themeId).titles().get("en").get(0));
		second.await("correct");
		JsonNode reveal = second.await("reveal");
		assertThat(reveal.path("index").intValue()).isEqualTo(0);
		assertThat(reveal.path("winner").asString(null)).isEqualTo("Tester");
	}

}
