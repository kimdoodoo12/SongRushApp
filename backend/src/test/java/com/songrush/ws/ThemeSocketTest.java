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
// {type:"roomTheme", theme}, and a theme that is not one of the id strings is bad-room-theme
// (docs/cycles/071-room-themes.md, revision M2).
// Cycle 109 (B-031): the ids are the eight moods; the old ids play, star and arcade arrive at every
// member as sunset, rain and pixel (docs/cycles/109-eight-moods.md).
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

	// The theme of every roomTheme frame received so far, in order.
	private static List<String> themesOf(TestClient client) {
		List<String> out = new ArrayList<>();
		for (String frame : client.received()) {
			JsonNode node = TestClient.MAPPER.readTree(frame);
			if ("roomTheme".equals(node.path("type").asString(null))) {
				out.add(node.path("theme").asString(null));
			}
		}
		return out;
	}

	private static void awaitThemeCount(TestClient client, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (themesOf(client).size() >= count) {
				return;
			}
			Thread.sleep(10);
		}
		fail("fewer than " + count + " roomTheme frames within " + TestClient.WAIT_MS + " ms: " + themesOf(client));
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
	void eachMoodRoundTripsToBothClients() throws Exception {
		List<TestClient> pair = hostAndGuest();
		TestClient host = pair.get(0);
		for (String mood : List.of("sunset", "rain", "sakura", "neon", "chalk", "pixel", "matsuri", "manga")) {
			int[] seen = new int[pair.size()];
			for (int i = 0; i < pair.size(); i++) {
				seen[i] = themesOf(pair.get(i)).size();
			}
			host.send("roomTheme", "theme", mood);
			for (int i = 0; i < pair.size(); i++) {
				TestClient client = pair.get(i);
				int before = seen[i];
				awaitThemeCount(client, before + 1);
				assertThat(themesOf(client).get(before)).as(mood).isEqualTo(mood);
			}
		}
	}

	@Test
	void theOldIdsArriveAsTheirMoods() throws Exception {
		List<TestClient> pair = hostAndGuest();
		TestClient host = pair.get(0);
		host.send("roomTheme", "theme", "star");
		host.send("roomTheme", "theme", "arcade");
		host.send("roomTheme", "theme", "play");
		for (TestClient client : pair) {
			awaitThemeCount(client, 3);
			assertThat(themesOf(client)).containsExactly("rain", "pixel", "sunset");
		}
	}

	@Test
	void aFreshLobbySendsNoRoomTheme() throws Exception {
		List<TestClient> pair = hostAndGuest();
		for (TestClient client : pair) {
			client.assertPingWorks();
			assertThat(frameTypes(client)).doesNotContain("roomTheme");
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
		host.send("roomTheme", "theme", "bogus");
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
