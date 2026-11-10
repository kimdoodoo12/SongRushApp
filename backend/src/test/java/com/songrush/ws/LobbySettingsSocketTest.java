package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;

// Cycle 116 (D-124, critic H1 of 114) acceptance 1: the lobby selection over the raw WebSocket at /ws
// on a real server, with songrush.data-dir pointing at a temp folder written before the context loads
// (as ThemeSocketTest does). The client message is {type:"lobbySettings", songCount, types?,
// yearFrom?, yearTo?, tier?, answerMode?, gameMode?}, the fields of start. It is checked in start's
// order: bad-message (non-integer songCount), bad-filter, bad-answer-mode, bad-game-mode, no-room,
// in-game, not-host, bad-song-count. A change reaches every member as {type:"lobbySettings",
// songCount, ...} with the default fields left out; an unchanged one is not broadcast. A joiner, a
// grace rejoin and a replaced socket in the lobby get the stored selection right after roomTheme
// (or roster, or snapshot); a late joiner during a game does not (docs/cycles/116-lobby-settings.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LobbySettingsSocketTest {

	private static final int SONGS = 8;

	private static final long QUIET_MS = 1_000;

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

	// ---- helpers ----

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

	// Every lobbySettings frame received so far, in order.
	private static List<JsonNode> lobbySettingsOf(TestClient client) {
		List<JsonNode> out = new ArrayList<>();
		for (String frame : client.received()) {
			JsonNode node = TestClient.MAPPER.readTree(frame);
			if ("lobbySettings".equals(node.path("type").asString(null))) {
				out.add(node);
			}
		}
		return out;
	}

	private static void awaitLobbySettingsCount(TestClient client, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			if (lobbySettingsOf(client).size() >= count) {
				return;
			}
			Thread.sleep(10);
		}
		fail("fewer than " + count + " lobbySettings frames within " + TestClient.WAIT_MS + " ms: "
				+ frameTypes(client));
	}

	// The token frame's value among the frames received so far, read without consuming the wait queue.
	private static String tokenOf(TestClient client) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			for (String frame : client.received()) {
				JsonNode node = TestClient.MAPPER.readTree(frame);
				if ("token".equals(node.path("type").asString(null))) {
					return node.path("token").asString(null);
				}
			}
			Thread.sleep(10);
		}
		return fail("no token frame within " + TestClient.WAIT_MS + " ms");
	}

	// The full selection: 12 songs, OP only, 2000-2010, easy, choice, ranked.
	private static void sendFull(TestClient client) throws Exception {
		client.send("lobbySettings", "songCount", 12, "types", List.of("OP"), "yearFrom", 2000, "yearTo", 2010,
				"tier", "easy", "answerMode", "choice", "gameMode", "ranked");
	}

	private static void assertFull(JsonNode node) {
		assertThat(node.propertyNames()).containsExactlyInAnyOrder("type", "songCount", "types", "yearFrom",
				"yearTo", "tier", "answerMode", "gameMode");
		assertThat(node.path("songCount").isInt()).isTrue();
		assertThat(node.path("songCount").intValue()).isEqualTo(12);
		assertThat(node.path("types").isArray()).isTrue();
		assertThat(node.path("types").size()).isEqualTo(1);
		assertThat(node.path("types").get(0).asString(null)).isEqualTo("OP");
		assertThat(node.path("yearFrom").intValue()).isEqualTo(2000);
		assertThat(node.path("yearTo").intValue()).isEqualTo(2010);
		assertThat(node.path("tier").asString(null)).isEqualTo("easy");
		assertThat(node.path("answerMode").asString(null)).isEqualTo("choice");
		assertThat(node.path("gameMode").asString(null)).isEqualTo("ranked");
	}

	private record Pair(TestClient host, TestClient guest, String code, String guestToken) {
	}

	// A host and a guest in one lobby, both past the two-player roster.
	private Pair hostAndGuest() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);
		String guestToken = tokenOf(guest);
		return new Pair(host, guest, code, guestToken);
	}

	// The host stores the full selection; both clients have received it.
	private void storeFull(Pair p) throws Exception {
		sendFull(p.host());
		assertFull(p.host().await("lobbySettings"));
		assertFull(p.guest().await("lobbySettings"));
	}

	// ---- the broadcast ----

	@Test
	void theHostsSelectionReachesBothClients() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);
	}

	@Test
	void defaultFieldsAreLeftOut() throws Exception {
		Pair p = hostAndGuest();
		p.host().send("lobbySettings", "songCount", 10, "tier", "normal", "answerMode", "chat", "gameMode", "first");
		for (TestClient client : List.of(p.host(), p.guest())) {
			JsonNode node = client.await("lobbySettings");
			assertThat(node.propertyNames()).containsExactlyInAnyOrder("type", "songCount");
			assertThat(node.path("songCount").intValue()).isEqualTo(10);
		}
	}

	@Test
	void anUnchangedSelectionIsNotBroadcast() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);
		sendFull(p.host());
		p.host().send("lobbySettings", "songCount", 20);
		for (TestClient client : List.of(p.host(), p.guest())) {
			awaitLobbySettingsCount(client, 2);
			List<JsonNode> seen = lobbySettingsOf(client);
			assertThat(seen.get(1).path("songCount").intValue()).as("the repeat was not broadcast").isEqualTo(20);
		}
		p.host().assertPingWorks();
		Thread.sleep(QUIET_MS);
		assertThat(lobbySettingsOf(p.host())).hasSize(2);
		assertThat(lobbySettingsOf(p.guest())).hasSize(2);
		assertThat(frameTypes(p.host())).doesNotContain("error");
	}

	// ---- errors, in start's order ----

	@Test
	void outsideARoomTheParsingErrorsComeBeforeNoRoom() throws Exception {
		TestClient client = connect();
		client.sendRaw("{\"type\":\"lobbySettings\",\"songCount\":\"12\",\"types\":[]}");
		client.awaitError("bad-message");
		client.sendRaw("{\"type\":\"lobbySettings\",\"types\":[]}");
		client.awaitError("bad-message");
		client.send("lobbySettings", "songCount", 12, "types", List.of(), "answerMode", "x", "gameMode", "x");
		client.awaitError("bad-filter");
		client.send("lobbySettings", "songCount", 12, "yearFrom", 2010, "yearTo", 2000, "answerMode", "x");
		client.awaitError("bad-filter");
		client.send("lobbySettings", "songCount", 12, "answerMode", "x", "gameMode", "x");
		client.awaitError("bad-answer-mode");
		client.sendRaw("{\"type\":\"lobbySettings\",\"songCount\":12,\"answerMode\":null}");
		client.awaitError("bad-answer-mode");
		client.send("lobbySettings", "songCount", 12, "gameMode", "x");
		client.awaitError("bad-game-mode");
		client.send("lobbySettings", "songCount", 99, "gameMode", "ranked");
		client.awaitError("no-room");
		client.send("lobbySettings", "songCount", 12);
		client.awaitError("no-room");
		client.assertPingWorks();
	}

	@Test
	void inTheLobbyAGuestGetsNotHostBeforeBadSongCount() throws Exception {
		Pair p = hostAndGuest();
		p.guest().send("lobbySettings", "songCount", 99);
		p.guest().awaitError("not-host");
		p.guest().send("lobbySettings", "songCount", 12, "types", List.of("XX"));
		p.guest().awaitError("bad-filter");
		p.host().send("lobbySettings", "songCount", 99);
		p.host().awaitError("bad-song-count");
		p.host().send("lobbySettings", "songCount", 4);
		p.host().awaitError("bad-song-count");
		p.host().assertPingWorks();
		p.guest().assertPingWorks();
		assertThat(frameTypes(p.host())).doesNotContain("lobbySettings");
		assertThat(frameTypes(p.guest())).doesNotContain("lobbySettings");
	}

	@Test
	void duringAGameEveryoneGetsInGame() throws Exception {
		Pair p = hostAndGuest();
		p.host().send("start", "songCount", 5);
		p.host().await("prepare");
		p.guest().await("prepare");
		p.host().send("lobbySettings", "songCount", 99);
		p.host().awaitError("in-game");
		p.guest().send("lobbySettings", "songCount", 99);
		p.guest().awaitError("in-game");
		p.host().send("lobbySettings", "songCount", 12);
		p.host().awaitError("in-game");
		assertThat(frameTypes(p.guest())).doesNotContain("lobbySettings");
	}

	// ---- who gets the stored selection ----

	@Test
	void aJoinerGetsItRightAfterJoinedRosterAndRoomTheme() throws Exception {
		Pair p = hostAndGuest();
		p.host().send("roomTheme", "theme", "neon");
		p.host().await("roomTheme");
		storeFull(p);

		TestClient third = connect();
		third.send("join", "code", p.code(), "nickname", "Third");
		assertFull(third.await("lobbySettings"));
		assertThat(frameTypes(third)).startsWith("joined", "roster", "roomTheme", "lobbySettings");
	}

	@Test
	void aJoinerWithTheDefaultThemeGetsItRightAfterRoster() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);
		TestClient third = connect();
		third.send("join", "code", p.code(), "nickname", "Third");
		assertFull(third.await("lobbySettings"));
		assertThat(frameTypes(third)).startsWith("joined", "roster", "lobbySettings");
	}

	@Test
	void aJoinerBeforeAnySelectionGetsNone() throws Exception {
		Pair p = hostAndGuest();
		TestClient third = connect();
		third.send("join", "code", p.code(), "nickname", "Third");
		third.await("joined");
		third.assertPingWorks();
		assertThat(frameTypes(third)).doesNotContain("lobbySettings");
	}

	@Test
	void aGraceRejoinGetsItRightAfterItsRoster() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);
		p.guest().close();
		awaitRoster(p.host(), 2);

		TestClient again = connect();
		again.send("rejoin", "token", p.guestToken());
		assertFull(again.await("lobbySettings"));
		assertThat(frameTypes(again)).startsWith("joined", "snapshot", "roster", "lobbySettings");
	}

	@Test
	void aReplacedSocketGetsItRightAfterItsSnapshot() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);

		// The guest's socket is still open: this rejoin replaces it.
		TestClient again = connect();
		again.send("rejoin", "token", p.guestToken());
		assertFull(again.await("lobbySettings"));
		assertThat(frameTypes(again)).startsWith("joined", "snapshot", "lobbySettings");
	}

	@Test
	void aLateJoinerDuringAGameGetsNone() throws Exception {
		Pair p = hostAndGuest();
		storeFull(p);
		p.host().send("start", "songCount", 5);
		p.host().await("prepare");

		TestClient third = connect();
		third.send("join", "code", p.code(), "nickname", "Third");
		third.await("snapshot");
		third.assertNone(Set.of("lobbySettings"), System.currentTimeMillis() + QUIET_MS);
		assertThat(frameTypes(third)).doesNotContain("lobbySettings");
	}

}
