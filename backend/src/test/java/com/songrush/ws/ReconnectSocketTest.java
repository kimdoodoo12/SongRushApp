package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.CloseStatus;

import tools.jackson.databind.JsonNode;

// Cycle 042 (B-005b) criterion 3: tokens, drop and rejoin, socket replacement (closed with 4001)
// and the rejoin errors over the raw WebSocket at /ws on a real server, with the data folder written
// before the context loads (as LobbySocketTest does). Waits are TestClient.WAIT_MS (15 s). The close
// status of a replaced socket is read through StatusClient (docs/cycles/042-reconnect.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconnectSocketTest {

	private static final int SONGS = 8;

	private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{22}";

	// How long a "nothing arrives" check listens.
	private static final long QUIET_MS = 2_000;

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

	private final List<StatusClient> statusClients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(TestClient::close);
		statusClients.forEach(StatusClient::close);
	}

	private TestClient connect() throws Exception {
		TestClient client = TestClient.connect(port);
		clients.add(client);
		return client;
	}

	private StatusClient connectWithStatus() throws Exception {
		StatusClient client = StatusClient.connect(port);
		statusClients.add(client);
		return client;
	}

	// ---- helpers ----

	// The first received frame of the type, whatever order it arrived in, within WAIT_MS. Reads the
	// list of every frame, so it does not consume the frames that await() reads.
	private static JsonNode firstReceived(Supplier<List<String>> received, String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			for (String frame : received.get()) {
				JsonNode node = TestClient.MAPPER.readTree(frame);
				if (node != null && node.isObject() && type.equals(node.path("type").asString(null))) {
					return node;
				}
			}
			Thread.sleep(20);
		}
		return fail("no '" + type + "' frame within " + TestClient.WAIT_MS + " ms");
	}

	private static String token(Supplier<List<String>> received) throws InterruptedException {
		JsonNode token = firstReceived(received, "token");
		assertThat(token.propertyNames()).as("token keys").containsExactlyInAnyOrder("type", "token");
		assertThat(token.path("token").isString()).isTrue();
		String value = token.path("token").stringValue();
		assertThat(value).as("token").matches(TOKEN_PATTERN);
		return value;
	}

	// Creates a lobby; returns its code.
	private static String create(TestClient client, String nickname) throws Exception {
		client.send("create", "nickname", nickname);
		JsonNode joined = client.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo(nickname);
		return joined.path("code").asString(null);
	}

	private static void join(TestClient client, String code, String nickname) throws Exception {
		client.send("join", "code", code, "nickname", nickname);
		JsonNode joined = client.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo(nickname);
	}

	private static List<String> rosterRows(JsonNode players) {
		List<String> rows = new ArrayList<>();
		for (JsonNode p : players) {
			assertThat(p.propertyNames()).containsExactlyInAnyOrder("name", "score", "host", "connected");
			rows.add(p.path("name").asString(null) + ":" + p.path("score").intValue() + ":"
					+ p.path("host").booleanValue() + ":" + p.path("connected").booleanValue());
		}
		return rows;
	}

	// The next roster whose rows satisfy the check; earlier rosters are skipped.
	private static JsonNode awaitRoster(TestClient client, Predicate<List<String>> check, String what)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			JsonNode roster = client.await("roster");
			if (check.test(rosterRows(roster.path("players")))) {
				return roster;
			}
		}
		return fail("no roster with " + what + " within " + TestClient.WAIT_MS + " ms");
	}

	private static JsonNode awaitRosterRows(TestClient client, String... rows) throws InterruptedException {
		List<String> expected = List.of(rows);
		return awaitRoster(client, expected::equals, expected.toString());
	}

	private static void assertLobbySnapshot(JsonNode snapshot, String... rows) {
		assertThat(snapshot.propertyNames()).as("snapshot keys").containsExactlyInAnyOrder("type", "phase",
				"players", "song", "scores");
		assertThat(snapshot.path("phase").asString(null)).isEqualTo("lobby");
		assertThat(snapshot.get("song").isNull()).as("song is null").isTrue();
		assertThat(snapshot.path("scores").isArray()).isTrue();
		assertThat(snapshot.path("scores").size()).isZero();
		assertThat(rosterRows(snapshot.path("players"))).containsExactly(rows);
	}

	// A in a lobby as host, B joined as Guest; returns B's token after both see the 2-player roster.
	private record Pair(TestClient host, TestClient guest, String code, String hostToken, String guestToken) {
	}

	private Pair pair() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		String code = create(host, "Host");
		String hostToken = token(host::received);
		join(guest, code, "Guest");
		String guestToken = token(guest::received);
		awaitRosterRows(host, "Host:0:true:true", "Guest:0:false:true");
		awaitRosterRows(guest, "Host:0:true:true", "Guest:0:false:true");
		return new Pair(host, guest, code, hostToken, guestToken);
	}

	// ---- tokens ----

	@Test
	void createAndJoinEachGetADistinctTokenThatNoOtherFrameCarries() throws Exception {
		Pair p = pair();
		assertThat(p.hostToken()).isNotEqualTo(p.guestToken());
		// One more round trip so any late frames are in.
		p.host().assertPingWorks();
		p.guest().assertPingWorks();
		for (TestClient client : List.of(p.host(), p.guest())) {
			List<String> frames = client.received();
			assertThat(frames.stream().filter(f -> f.contains("\"token\"")).count()).as("one token frame")
					.isEqualTo(1);
			for (String frame : frames) {
				JsonNode node = TestClient.MAPPER.readTree(frame);
				if ("token".equals(node.path("type").asString(null))) {
					continue;
				}
				assertThat(frame).as("frame %s", node.path("type").asString(null))
						.doesNotContain(p.hostToken())
						.doesNotContain(p.guestToken());
			}
		}
	}

	@Test
	void soloCreateAlsoGetsAToken() throws Exception {
		TestClient solo = connect();
		solo.send("create", "nickname", "Solo", "songCount", 5);
		solo.await("joined");
		token(solo::received);
		solo.await("prepare");
	}

	// ---- drop and rejoin ----

	@Test
	void droppedSocketShowsDisconnectedAndARejoinRestoresTheSeat() throws Exception {
		Pair p = pair();
		p.guest().close();
		awaitRosterRows(p.host(), "Host:0:true:true", "Guest:0:false:false");

		TestClient again = connect();
		again.send("rejoin", "token", p.guestToken());
		JsonNode joined = again.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo("Guest");
		assertThat(joined.path("code").asString(null)).isEqualTo(p.code());
		JsonNode snapshot = again.await("snapshot");
		assertLobbySnapshot(snapshot, "Host:0:true:true", "Guest:0:false:true");
		awaitRosterRows(p.host(), "Host:0:true:true", "Guest:0:false:true");
		for (String frame : again.received()) {
			assertThat(frame).doesNotContain(p.guestToken());
		}

		// The rejoined session holds the seat: its leave removes Guest.
		again.send("leave");
		awaitRosterRows(p.host(), "Host:0:true:true");
	}

	// ---- replacement ----

	@Test
	void aSecondRejoinReplacesTheOpenSocketWith4001AndSendsNoRoster() throws Exception {
		Pair p = pair();
		p.guest().close();
		awaitRosterRows(p.host(), "Host:0:true:true", "Guest:0:false:false");

		StatusClient second = connectWithStatus();
		second.send("rejoin", "token", p.guestToken());
		second.await("joined");
		second.await("snapshot");
		awaitRosterRows(p.host(), "Host:0:true:true", "Guest:0:false:true");

		TestClient third = connect();
		third.send("rejoin", "token", p.guestToken());
		CloseStatus status = second.awaitClose();
		assertThat(status.getCode()).as("close status of the replaced socket").isEqualTo(4001);
		JsonNode joined = third.await("joined");
		assertThat(joined.path("name").asString(null)).isEqualTo("Guest");
		JsonNode snapshot = third.await("snapshot");
		assertLobbySnapshot(snapshot, "Host:0:true:true", "Guest:0:false:true");

		// No roster at all from the replacement, including the replaced socket's late close.
		p.host().assertNone(Set.of("roster"), System.currentTimeMillis() + QUIET_MS);
		third.assertPingWorks();

		// The third socket holds the seat: its leave removes Guest.
		third.send("leave");
		awaitRosterRows(p.host(), "Host:0:true:true");
	}

	// ---- errors ----

	@Test
	void unknownTokenGetsRejoinFailed() throws Exception {
		TestClient client = connect();
		client.send("rejoin", "token", "AAAAAAAAAAAAAAAAAAAAAA");
		client.awaitError("rejoin-failed");
		client.assertPingWorks();
		// The session is still free.
		create(client, "Fresh");
	}

	@Test
	void nonStringTokenGetsBadMessage() throws Exception {
		TestClient client = connect();
		client.send("rejoin", "token", 12345);
		client.awaitError("bad-message");
		client.send("rejoin");
		client.awaitError("bad-message");
		client.assertPingWorks();
	}

	@Test
	void rejoinFromASeatedSessionGetsAlreadyInRoom() throws Exception {
		Pair p = pair();
		p.host().send("rejoin", "token", p.guestToken());
		p.host().awaitError("already-in-room");
		p.guest().send("rejoin", "token", p.guestToken());
		p.guest().awaitError("already-in-room");
		p.host().assertNone(Set.of("roster", "snapshot"), System.currentTimeMillis() + QUIET_MS);
	}

	@Test
	void afterLeaveTheOldTokenGetsRejoinFailed() throws Exception {
		Pair p = pair();
		p.guest().send("leave");
		awaitRosterRows(p.host(), "Host:0:true:true");

		TestClient again = connect();
		again.send("rejoin", "token", p.guestToken());
		again.awaitError("rejoin-failed");
		p.host().assertNone(Set.of("roster"), System.currentTimeMillis() + QUIET_MS);
	}

}
