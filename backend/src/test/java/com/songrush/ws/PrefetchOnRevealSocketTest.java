package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.GameProperties;

import tools.jackson.databind.JsonNode;

// Cycle 107 (D-119) items 1 and 2 end to end: with songrush.game.prefetch-on-reveal=true the
// GameProperties bean is true and a real server sends the next song's prepare, with holdMs, right
// after the reveal, while the song itself still starts only after the 5 s reveal
// (docs/cycles/107-prefetch-on-reveal.md). Wall-clock bounds are loose: the point is "about 0 s"
// versus "about 5 s". The API is described in GamePropertiesBindingTest.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "songrush.game.prefetch-on-reveal=true")
class PrefetchOnRevealSocketTest {

	private static final int SONGS = 8;

	private static final long REVEAL_MS = 5000;

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
	private GameProperties properties;

	private final List<TestClient> clients = new ArrayList<>();

	@AfterEach
	void closeClients() {
		clients.forEach(TestClient::close);
	}

	@Test
	void thePropertyBindsTrue() {
		assertThat(properties.prefetchOnReveal()).isTrue();
	}

	@Test
	void theNextPrepareArrivesWithTheRevealAndTheSongStartsAfterIt() throws Exception {
		TestClient client = TestClient.connect(port);
		clients.add(client);
		client.send("create", "nickname", "Tester", "songCount", 5);
		JsonNode first = client.await("prepare");
		assertThat(first.path("index").intValue()).isEqualTo(0);
		assertThat(first.has("holdMs")).as("the game's first prepare has no holdMs").isFalse();
		client.send("ready", "index", 0);
		client.await("start");
		client.send("skipVote", "index", 0);

		JsonNode reveal = client.await("reveal");
		long revealAt = System.currentTimeMillis();
		assertThat(reveal.path("index").intValue()).isEqualTo(0);
		JsonNode prepare = client.await("prepare");
		long prepareAt = System.currentTimeMillis();
		assertThat(prepare.path("index").intValue()).isEqualTo(1);
		assertThat(prepare.path("holdMs").isIntegralNumber()).as("holdMs present").isTrue();
		assertThat(prepare.path("holdMs").longValue()).isEqualTo(REVEAL_MS);
		assertThat(prepareAt - revealAt).as("prepare comes with the reveal, not after it").isLessThan(2000);

		client.send("ready", "index", 1);
		JsonNode start = client.await("start");
		long startRecv = System.currentTimeMillis();
		assertThat(start.path("index").intValue()).isEqualTo(1);
		assertThat(startRecv - revealAt).as("start waits for the reveal to end").isGreaterThanOrEqualTo(REVEAL_MS - 500);
		assertThat(start.path("startAt").longValue() - start.path("serverTime").longValue()).isEqualTo(3000);
	}

}
