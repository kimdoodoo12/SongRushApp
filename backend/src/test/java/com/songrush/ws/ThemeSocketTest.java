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

// Cycle 071 (F-012) criterion 2: the room theme over the raw WebSocket at /ws on a real server, with
// songrush.data-dir pointing at a temp folder written before the context loads (as LobbySocketTest
// does). The client message is {type:"roomTheme", theme}; a change reaches every member as
// {type:"roomTheme", theme}, and a theme that is not one of the six id strings is bad-room-theme
// (docs/cycles/071-room-themes.md, revision M2).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ThemeSocketTest {

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

	private static List<String> frameTypes(TestClient client) {
		List<String> out = new ArrayList<>();
		for (String frame : client.received()) {
			out.add(TestClient.MAPPER.readTree(frame).path("type").asString(null));
		}
		return out;
	}

	// A host and a guest in one lobby, both past the two-player roster.
	private List<TestClient> hostAndGuest() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);
		return List.of(host, guest);
	}

	@Test
	void theHostsRoomThemeReachesBothClients() throws Exception {
		List<TestClient> pair = hostAndGuest();
		TestClient host = pair.get(0);
		host.send("roomTheme", "theme", "sakura");
		for (TestClient client : pair) {
			JsonNode theme = client.await("roomTheme");
			assertThat(theme.propertyNames()).containsExactlyInAnyOrder("type", "theme");
			assertThat(theme.path("theme").asString(null)).isEqualTo("sakura");
		}
	}

	@Test
	void aNonStringThemeIsBadRoomTheme() throws Exception {
		List<TestClient> pair = hostAndGuest();
		TestClient host = pair.get(0);
		TestClient guest = pair.get(1);
		host.sendRaw("{\"type\":\"roomTheme\",\"theme\":5}");
		host.awaitError("bad-room-theme");
		host.sendRaw("{\"type\":\"roomTheme\"}");
		host.awaitError("bad-room-theme");
		host.send("roomTheme", "theme", "lava");
		host.awaitError("bad-room-theme");
		host.assertPingWorks();
		guest.assertPingWorks();
		assertThat(frameTypes(host)).doesNotContain("roomTheme");
		assertThat(frameTypes(guest)).doesNotContain("roomTheme", "error");

		// The lobby is untouched: a valid change still works.
		host.send("roomTheme", "theme", "neon");
		for (TestClient client : pair) {
			assertThat(client.await("roomTheme").path("theme").asString(null)).isEqualTo("neon");
		}
	}

	@Test
	void aGuestsRoomThemeIsNotHost() throws Exception {
		List<TestClient> pair = hostAndGuest();
		TestClient host = pair.get(0);
		TestClient guest = pair.get(1);
		guest.send("roomTheme", "theme", "chalk");
		guest.awaitError("not-host");
		host.assertPingWorks();
		guest.assertPingWorks();
		assertThat(frameTypes(host)).doesNotContain("roomTheme", "error");
		assertThat(frameTypes(guest)).doesNotContain("roomTheme");
	}

}
