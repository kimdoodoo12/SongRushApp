package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;

// Cycle 046 (B-006c) criterion 3: a third client joins a running game by code over the raw
// WebSocket at /ws on a real server, with songrush.data-dir pointing at a temp folder written before
// the context loads (as LobbySocketTest does). Waits are TestClient.WAIT_MS (15 s)
// (docs/cycles/046-late-joiners.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LateJoinSocketTest {

	private static final int SONGS = 8;

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		WsFixture.write(dataDir, SONGS);
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

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

	@Test
	void aThirdClientJoinsARunningGameAndGetsTheSnapshotThenTheNextPrepare() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);

		host.send("start", "songCount", 5);
		for (TestClient client : List.of(host, guest)) {
			assertThat(client.await("prepare").path("index").intValue()).isEqualTo(0);
		}

		TestClient late = connect();
		late.send("join", "code", code, "nickname", "Late");
		JsonNode joined = late.await("joined");
		assertThat(joined.path("code").asString(null)).isEqualTo(code);
		assertThat(joined.path("name").asString(null)).isEqualTo("Late");
		JsonNode snapshot = late.await("snapshot");
		assertThat(snapshot.path("phase").asString(null)).isEqualTo("playing");
		assertThat(snapshot.path("song").path("index").intValue()).isEqualTo(0);
		assertThat(snapshot.path("song").path("total").intValue()).isEqualTo(5);
		for (TestClient client : List.of(host, guest, late)) {
			awaitRoster(client, 3);
		}

		// The two Room players end song 0 with a skip; the late joiner gets song 1's prepare.
		host.send("ready", "index", 0);
		guest.send("ready", "index", 0);
		for (TestClient client : List.of(host, guest)) {
			assertThat(client.await("start").path("index").intValue()).isEqualTo(0);
		}
		host.send("skipVote", "index", 0);
		guest.send("skipVote", "index", 0);
		JsonNode prepare = late.await("prepare");
		assertThat(prepare.path("index").intValue()).isEqualTo(1);
		assertThat(prepare.path("total").intValue()).isEqualTo(5);
		assertThat(prepare.path("audioUrl").asString("")).startsWith("/audio/");
	}

}
