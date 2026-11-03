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

// Cycle 045 (B-006b) criterion 3: skipVote{index} over the raw WebSocket at /ws on a real server,
// with songrush.data-dir pointing at a temp folder written before the context loads (as
// LobbySocketTest does). Waits are TestClient.WAIT_MS (15 s) (docs/cycles/045-multiplayer-rounds.md).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RoundsSocketTest {

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

	// The next skipState with the given vote count; earlier skipStates are skipped.
	private static JsonNode awaitVotes(TestClient client, int votes) throws InterruptedException {
		long deadline = System.currentTimeMillis() + TestClient.WAIT_MS;
		while (System.currentTimeMillis() < deadline) {
			JsonNode state = client.await("skipState");
			assertThat(state.propertyNames()).containsExactlyInAnyOrder("type", "index", "votes", "needed");
			if (state.path("votes").intValue() == votes) {
				return state;
			}
		}
		return fail("no skipState with " + votes + " votes within " + TestClient.WAIT_MS + " ms");
	}

	// Host creates a lobby and the guest joins it; both see a roster of two.
	private void createAndJoin(TestClient host, TestClient guest) throws Exception {
		host.send("create", "nickname", "Host");
		String code = host.await("joined").path("code").asString(null);
		assertThat(code).isNotNull();
		guest.send("join", "code", code, "nickname", "Guest");
		guest.await("joined");
		awaitRoster(host, 2);
		awaitRoster(guest, 2);
	}

	@Test
	void skipVotesFromBothPlayersEndTheSongWithNoWinner() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		createAndJoin(host, guest);

		host.send("start", "songCount", 5);
		for (TestClient client : List.of(host, guest)) {
			JsonNode prepare = client.await("prepare");
			assertThat(prepare.path("index").intValue()).isEqualTo(0);
		}
		host.send("ready", "index", 0);
		guest.send("ready", "index", 0);
		for (TestClient client : List.of(host, guest)) {
			JsonNode start = client.await("start");
			assertThat(start.path("index").intValue()).isEqualTo(0);
		}

		host.send("skipVote", "index", 0);
		guest.send("skipVote", "index", 0);
		for (TestClient client : List.of(host, guest)) {
			JsonNode state = awaitVotes(client, 2);
			assertThat(state.path("index").intValue()).isEqualTo(0);
			assertThat(state.path("needed").intValue()).isEqualTo(2);
			// The two-vote skipState came first, so the song ended by the skip and not by the deadline
			// (after a reveal the Room ignores skip votes, so no two-vote skipState could follow it).
			JsonNode reveal = client.await("reveal");
			assertThat(reveal.path("index").intValue()).isEqualTo(0);
			assertThat(reveal.has("winner")).isTrue();
			assertThat(reveal.path("winner").isNull()).as("winner is null").isTrue();
		}
	}

	@Test
	void aSkipVoteWithoutAnIntegerIndexIsABadMessage() throws Exception {
		TestClient host = connect();
		TestClient guest = connect();
		createAndJoin(host, guest);

		host.send("skipVote", "index", "x");
		host.awaitError("bad-message");
		host.send("skipVote");
		host.awaitError("bad-message");
		host.assertPingWorks();
	}

	@Test
	void aSkipVoteWithNoRoomGetsNoRoom() throws Exception {
		TestClient client = connect();
		client.send("skipVote", "index", 0);
		client.awaitError("no-room");
		client.assertPingWorks();
	}

}
