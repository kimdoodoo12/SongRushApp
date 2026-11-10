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

// Cycle 113 (B-032, D-123) acceptance 2: lobby chat over the raw WebSocket at /ws on a real server,
// with songrush.data-dir pointing at a temp folder written before the context loads (as
// ThemeSocketTest does). Two clients in a fresh lobby, no game started: a {type:"chat", text} from
// one arrives at both as {type:"chat", name, text} (docs/cycles/113-lobby-chat.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbyChatSocketTest {

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
	void aLobbyChatFromOneClientArrivesAtBoth() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		assertThat(awaitRoster(host, 2).path("phase").asString(null)).isEqualTo("lobby");
		awaitRoster(guest, 2);

		guest.send("chat", "text", "hello lobby");
		for (TestClient client : List.of(host, guest)) {
			JsonNode line = client.await("chat");
			assertThat(line.propertyNames()).containsExactlyInAnyOrder("type", "name", "text");
			assertThat(line.path("name").asString(null)).isEqualTo("Guest");
			assertThat(line.path("text").asString(null)).isEqualTo("hello lobby");
		}

		host.send("chat", "text", "welcome");
		for (TestClient client : List.of(host, guest)) {
			JsonNode line = client.await("chat");
			assertThat(line.path("name").asString(null)).isEqualTo("Host");
			assertThat(line.path("text").asString(null)).isEqualTo("welcome");
		}
	}

}
