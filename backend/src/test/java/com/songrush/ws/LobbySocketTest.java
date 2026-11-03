package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;

// Cycle 040 (B-005a) criterion 3: rooms and the lobby over the raw WebSocket at /ws on a real server,
// with songrush.data-dir pointing at a temp folder written before the context loads (as
// SoloSocketTest does). Waits are TestClient.WAIT_MS (15 s); a roster wait skips rosters until one
// with the expected player count arrives (docs/cycles/040-rooms-lobby.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbySocketTest {

	private static final int SONGS = 8;

	private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

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

	private static void assertCode(String code) {
		assertThat(code).as("room code").isNotNull().hasSize(5);
		for (char ch : code.toCharArray()) {
			assertThat(ALPHABET.indexOf(ch)).as("code char %s", ch).isGreaterThanOrEqualTo(0);
		}
	}

	// Creates a lobby and returns its code from the joined frame.
	private static String create(TestClient client, String nickname) throws Exception {
		client.send("create", "nickname", nickname);
		JsonNode joined = client.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo(nickname);
		String code = joined.path("code").asString(null);
		assertCode(code);
		return code;
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

	private static void assertRosterShape(JsonNode roster) {
		assertThat(roster.propertyNames()).containsExactlyInAnyOrder("type", "phase", "players");
		for (JsonNode player : roster.path("players")) {
			assertThat(player.propertyNames()).containsExactlyInAnyOrder("name", "score", "host", "connected");
		}
	}

	private static List<String> rosterRows(JsonNode roster) {
		List<String> rows = new ArrayList<>();
		for (JsonNode p : roster.path("players")) {
			rows.add(p.path("name").asString(null) + ":" + p.path("score").intValue() + ":"
					+ p.path("host").booleanValue() + ":" + p.path("connected").booleanValue());
		}
		return rows;
	}

	@Test
	void createJoinStartAndLeaveOverTheSocket() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();

		String code = create(host, "Host");
		JsonNode solo = awaitRoster(host, 1);
		assertThat(solo.path("phase").asString(null)).isEqualTo("lobby");
		assertThat(rosterRows(solo)).containsExactly("Host:0:true:true");

		guest.send("join", "code", code.toLowerCase(Locale.ROOT), "nickname", "Guest");
		JsonNode joined = guest.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo("Guest");
		assertThat(joined.path("code").asString(null)).isEqualTo(code);

		for (TestClient client : List.of(host, guest)) {
			JsonNode roster = awaitRoster(client, 2);
			assertRosterShape(roster);
			assertThat(roster.path("phase").asString(null)).isEqualTo("lobby");
			assertThat(rosterRows(roster)).containsExactly("Host:0:true:true", "Guest:0:false:true");
		}

		guest.send("start", "songCount", 5);
		guest.awaitError("not-host");

		host.send("start", "songCount", 5);
		for (TestClient client : List.of(host, guest)) {
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(0);
			assertThat(prepare.path("total").intValue()).isEqualTo(5);
		}

		host.send("leave");
		guest.send("leave");
		// The sessions are free again: each can create a new lobby at once.
		String hostCode = create(host, "Host");
		String guestCode = create(guest, "Guest");
		assertThat(hostCode).isNotEqualTo(guestCode);
	}

	@Test
	void unknownCodeGetsRoomNotFound() throws Exception {
		TestClient client = connect();
		// '1' is not in the alphabet, so no lobby can have this code.
		client.send("join", "code", "11111", "nickname", "Lost");
		client.awaitError("room-not-found");
		client.assertPingWorks();
	}

	@Test
	void duplicateNicknameGetsANumber() throws Exception {
		TestClient first = connect();
		TestClient second = connect();
		String code = create(first, "Name");
		second.send("join", "code", code, "nickname", "Name");
		JsonNode joined = second.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo("Name (2)");
		JsonNode roster = awaitRoster(first, 2);
		assertThat(rosterRows(roster)).containsExactly("Name:0:true:true", "Name (2):0:false:true");
	}

	@Test
	void badNicknameLeavesNoOrphanSlot() throws Exception {
		TestClient client = connect();
		client.send("create", "nickname", "");
		client.awaitError("bad-nickname");
		create(client, "Valid");
		awaitRoster(client, 1);
	}

}
