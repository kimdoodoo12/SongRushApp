package com.songrush.ws;

import static org.assertj.core.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;

// A test WebSocket client on /ws that, unlike the cycle 037 TestClient, exposes the status the
// socket was closed with (cycle 042: a replaced socket is closed with 4001). Received text frames
// go into a BlockingQueue for waits and a list of every frame; waits skip frames of other types.
final class StatusClient extends TextWebSocketHandler {

	static final long WAIT_MS = TestClient.WAIT_MS;

	private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();

	private final List<String> all = new CopyOnWriteArrayList<>();

	private final CountDownLatch closed = new CountDownLatch(1);

	private volatile CloseStatus closeStatus;

	private WebSocketSession session;

	private StatusClient() {
	}

	static StatusClient connect(int port) throws Exception {
		StatusClient client = new StatusClient();
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
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		closeStatus = status;
		closed.countDown();
	}

	// Every frame received so far, in arrival order.
	List<String> received() {
		return List.copyOf(all);
	}

	// A JSON object frame: "type" first, then the keys and values in order.
	void send(String type, Object... keysAndValues) throws Exception {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		session.sendMessage(new TextMessage(TestClient.MAPPER.writeValueAsString(m)));
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
			JsonNode node = TestClient.MAPPER.readTree(frame);
			if (node != null && node.isObject() && type.equals(node.path("type").asString(null))) {
				return node;
			}
		}
	}

	// The status the server closed this socket with, waiting up to WAIT_MS for the close.
	CloseStatus awaitClose() throws InterruptedException {
		if (!closed.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
			return fail("socket not closed within " + WAIT_MS + " ms");
		}
		return closeStatus;
	}

	void close() {
		try {
			if (session != null && session.isOpen()) {
				session.close();
			}
		}
		catch (Exception e) {
			// Closing at test teardown: the server side is what is under test, so only report it.
			System.err.println("status client close failed: " + e);
		}
	}

}
