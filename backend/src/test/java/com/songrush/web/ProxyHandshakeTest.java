package com.songrush.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 054 (B-009) criterion 1: behind a TLS-terminating proxy the WebSocket same-origin check
// sees https, and the remote address comes from X-Forwarded-For (server.forward-headers-strategy).
// The success case uses Spring's StandardWebSocketClient with a "Host" header (the JDK HttpClient
// forbids Host), through HostHeaderWebSocketClient since the public execute drops Host; the refusals
// are raw HTTP/1.1 upgrade requests over a Socket (spec review M1).
// The remote address is recorded by a test-only filter imported here, not component-scanned.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProxyHandshakeTest.RemoteAddrRecorder.class)
class ProxyHandshakeTest {

	private static final long WAIT_MS = 15_000;

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		WebFixture.writeData(dataDir);
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

	// Records request.getRemoteAddr() of the last /health request. A plain class, so only the
	// @Import above registers it (Boot adds Filter beans to the servlet context).
	static final class RemoteAddrRecorder implements Filter {

		static final AtomicReference<String> LAST_HEALTH = new AtomicReference<>();

		@Override
		public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			if (request instanceof HttpServletRequest http && "/health".equals(http.getRequestURI())) {
				LAST_HEALTH.set(request.getRemoteAddr());
			}
			chain.doFilter(request, response);
		}

	}

	private static final class Frames extends TextWebSocketHandler {

		final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

		@Override
		protected void handleTextMessage(WebSocketSession session, TextMessage message) {
			queue.add(message.getPayload());
		}

		JsonNode await(String type) throws InterruptedException {
			long deadline = System.currentTimeMillis() + WAIT_MS;
			while (true) {
				long left = deadline - System.currentTimeMillis();
				String frame = left <= 0 ? null : queue.poll(left, TimeUnit.MILLISECONDS);
				if (frame == null) {
					return fail("no '" + type + "' frame within " + WAIT_MS + " ms");
				}
				JsonNode node = MAPPER.readTree(frame);
				if (node != null && node.isObject() && type.equals(node.path("type").asString(null))) {
					return node;
				}
			}
		}

	}

	private int rawUpgrade(String origin, boolean forwardedProto) {
		StringBuilder request = new StringBuilder()
				.append("GET /ws HTTP/1.1\r\n")
				.append("Host: play.test\r\n")
				.append("Upgrade: websocket\r\n")
				.append("Connection: Upgrade\r\n")
				.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
				.append("Sec-WebSocket-Version: 13\r\n")
				.append("Origin: ").append(origin).append("\r\n");
		if (forwardedProto) {
			request.append("X-Forwarded-Proto: https\r\n");
		}
		request.append("\r\n");
		return WebFixture.raw(port, request.toString()).status();
	}

	@Test
	void proxiedHandshakeFromTheSameHttpsOriginSucceeds() throws Exception {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.set("Host", "play.test");
		headers.set("Origin", "https://play.test");
		headers.set("X-Forwarded-Proto", "https");
		Frames frames = new Frames();
		WebSocketSession session = new HostHeaderWebSocketClient()
				.connect(frames, headers, URI.create("ws://127.0.0.1:" + port + "/ws"))
				.get(WAIT_MS, TimeUnit.MILLISECONDS);
		try {
			session.sendMessage(new TextMessage("{\"type\":\"create\",\"nickname\":\"A\"}"));
			JsonNode joined = frames.await("joined");
			assertThat(joined.path("name").asString(null)).isEqualTo("A");
		}
		finally {
			session.close();
		}
	}

	@Test
	void proxiedHandshakeFromAnotherOriginIsRefused() {
		assertThat(rawUpgrade("https://evil.test", true)).isEqualTo(403);
	}

	// Pins that X-Forwarded-Proto is what makes the https origin match.
	@Test
	void httpsOriginWithoutForwardedProtoIsRefused() {
		assertThat(rawUpgrade("https://play.test", false)).isEqualTo(403);
	}

	@Test
	void remoteAddressComesFromForwardedFor() throws Exception {
		RemoteAddrRecorder.LAST_HEALTH.set(null);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
				.timeout(Duration.ofSeconds(10))
				.header("X-Forwarded-For", "203.0.113.7")
				.GET()
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(RemoteAddrRecorder.LAST_HEALTH.get()).isEqualTo("203.0.113.7");
	}

}
