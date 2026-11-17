package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// A test WebSocket client on /ws: every received text frame goes into a BlockingQueue (for waits)
// and a list of every frame (for answer-leak checks). Waits skip frames of other types.
final class TestClient extends TextWebSocketHandler {

	static final long WAIT_MS = 15_000;

	static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final AtomicInteger PING_IDS = new AtomicInteger(1000);

	static final String CAPTURE_PROPERTY = "songrush.captureFrames";

	// Frame type to the length of the frame captured for it in this JVM (guarded by itself).
	private static final Map<String, Integer> CAPTURED = new HashMap<>();

	private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

	private final List<String> all = new CopyOnWriteArrayList<>();

	private volatile CloseStatus closeStatus;

	private WebSocketSession session;

	private TestClient() {
	}

	static TestClient connect(int port) throws Exception {
		TestClient client = new TestClient();
		client.session = new StandardWebSocketClient()
				.execute(client, "ws://localhost:" + port + "/ws")
				.get(WAIT_MS, TimeUnit.MILLISECONDS);
		return client;
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		String payload = message.getPayload();
		all.add(payload);
		frames.add(payload);
		capture(payload);
	}

	// Opt-in example capture for contracts/protocol.md (cycle 144): with the system property
	// songrush.captureFrames set to a folder, each received JSON object frame is written there as
	// <type>.json, pretty-printed with LF line ends. Per type the largest frame of this run is kept,
	// so the richest one (prepare with choices, reveal with nextChoices) wins. Unset: nothing is written.
	static void capture(String payload) {
		String folder = System.getProperty(CAPTURE_PROPERTY);
		if (folder == null || folder.isBlank()) {
			return;
		}
		JsonNode node = MAPPER.readTree(payload);
		if (node == null || !node.isObject() || !node.path("type").isString()) {
			return;
		}
		String type = node.path("type").stringValue();
		synchronized (CAPTURED) {
			Integer best = CAPTURED.get(type);
			if (best != null && best >= payload.length()) {
				return;
			}
			String pretty = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node).replace("\r\n", "\n") + "\n";
			try {
				Path dir = Path.of(folder);
				Files.createDirectories(dir);
				Files.writeString(dir.resolve(type + ".json"), pretty, StandardCharsets.UTF_8);
			}
			catch (IOException e) {
				// Asked-for capture that cannot write must fail loudly, not leave a stale example behind.
				throw new UncheckedIOException("frame capture to " + folder + " failed", e);
			}
			CAPTURED.put(type, payload.length());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		closeStatus = status;
	}

	boolean isOpen() {
		return session.isOpen() && closeStatus == null;
	}

	// Every frame received so far, in arrival order.
	List<String> received() {
		return List.copyOf(all);
	}

	void sendRaw(String text) throws Exception {
		session.sendMessage(new TextMessage(text));
	}

	// A JSON object frame: "type" first, then the keys and values in order.
	void send(String type, Object... keysAndValues) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		sendRaw(MAPPER.writeValueAsString(m));
	}

	// The next frame of the given type within WAIT_MS; frames of other types are skipped.
	JsonNode await(String type) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (true) {
			long left = deadline - System.currentTimeMillis();
			String frame = left <= 0 ? null : frames.poll(left, TimeUnit.MILLISECONDS);
			if (frame == null) {
				return fail("no '" + type + "' frame within " + WAIT_MS + " ms; closed=" + closeStatus);
			}
			JsonNode node = MAPPER.readTree(frame);
			if (node != null && node.isObject() && type.equals(node.path("type").asString(null))) {
				return node;
			}
		}
	}

	void awaitError(String code) throws InterruptedException {
		JsonNode error = await("error");
		assertThat(error.path("code").asString(null)).as("error code").isEqualTo(code);
	}

	// Fails if a frame of one of the types arrives before the wall clock reaches untilMillis.
	void assertNone(Set<String> types, long untilMillis) throws InterruptedException {
		while (true) {
			long left = untilMillis - System.currentTimeMillis();
			if (left <= 0) {
				return;
			}
			String frame = frames.poll(left, TimeUnit.MILLISECONDS);
			if (frame == null) {
				return;
			}
			JsonNode node = MAPPER.readTree(frame);
			String type = node != null && node.isObject() ? node.path("type").asString(null) : null;
			assertThat(types).as("unexpected frame %s", type).doesNotContain(type);
		}
	}

	// A ping with a fresh id gets its pong, so the socket is open and the handler still answers.
	void assertPingWorks() throws Exception {
		int id = PING_IDS.incrementAndGet();
		long before = System.currentTimeMillis();
		send("ping", "id", id);
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (true) {
			JsonNode pong = await("pong");
			if (pong.path("id").isIntegralNumber() && pong.path("id").intValue() == id) {
				long serverTime = pong.path("serverTime").longValue();
				assertThat(serverTime).isBetween(before, System.currentTimeMillis());
				assertThat(isOpen()).isTrue();
				return;
			}
			if (System.currentTimeMillis() > deadline) {
				fail("no pong for id " + id);
			}
		}
	}

	void close() {
		try {
			if (session != null && session.isOpen()) {
				session.close();
			}
		}
		catch (Exception e) {
			// Closing at test teardown: the server side is what is under test, so only report it.
			System.err.println("test client close failed: " + e);
		}
	}

}
