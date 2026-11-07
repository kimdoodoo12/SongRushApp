package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 082 backend item 4 (D-096): the GitHub call, against a JDK HttpServer on 127.0.0.1.
//
// Production API this test relies on (package com.songrush.feedback):
//   public class GitHubIssueSender implements IssueSender {
//     public GitHubIssueSender(FeedbackProperties properties, Duration connectTimeout, Duration requestTimeout);
//     public void send(Issue issue) throws IssueSendException;
//   }
//   - one POST to {apiBase}/repos/{repo}/issues with exactly these headers:
//       Authorization: Bearer <token>
//       Accept: application/vnd.github+json
//       X-GitHub-Api-Version: 2022-11-28
//       User-Agent: SongRushBot/1.0 (+https://animeopguessr.com)
//       Content-Type: application/json
//     and the JSON body {"title": ..., "body": ..., "labels": [...]} taken unchanged from the Issue;
//   - a 2xx answer returns normally; a non-2xx answer, a timeout, a refused connection or any
//     other exception throws IssueSendException; no retries;
//   - no exception message (nor any cause's) contains the token;
//   - the sender logs the GitHub status; on 401/403 it logs at ERROR exactly
//     "feedback: GitHub rejected the token (status N) - renew SONGRUSH_FEEDBACK_TOKEN";
//     no log line contains the token or the issue's title/body text.
//   The production wiring uses connect 10 s and request 20 s; tests pass short values.
@ExtendWith(OutputCaptureExtension.class)
class GitHubIssueSenderTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final String TOKEN = "t0k-secret";
	private static final String REPO = "kimdoodoo12/SongRushApp";

	private static final Issue ISSUE = new Issue("[문의/버그] title \"quoted\" " + (char) 0xFF20 + "x",
			"- 분류: 버그\n\n````text\nline1\n\tline2 ``` </script>\n````\n\n_사이트 문의하기로 접수됨_",
			List.of("문의", "bug"));

	record Captured(String method, String path, Headers headers, byte[] body) {
	}

	private final List<Captured> requests = new CopyOnWriteArrayList<>();
	private final CountDownLatch release = new CountDownLatch(1);

	private HttpServer server;
	private ExecutorService pool;
	private volatile int status = 201;
	private volatile boolean hang;

	@BeforeEach
	void startFakeGitHub() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		pool = Executors.newCachedThreadPool();
		server.setExecutor(pool);
		server.createContext("/", exchange -> {
			byte[] body;
			try (InputStream in = exchange.getRequestBody()) {
				body = in.readAllBytes();
			}
			requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
					exchange.getRequestHeaders(), body));
			if (hang) {
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] answer = "{\"number\":7,\"message\":\"fake\"}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, answer.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(answer);
			}
		});
		server.start();
	}

	@AfterEach
	void stopFakeGitHub() {
		release.countDown();
		server.stop(0);
		pool.shutdownNow();
	}

	private String apiBase() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private GitHubIssueSender sender(String apiBase, Duration requestTimeout) {
		return new GitHubIssueSender(new FeedbackProperties(TOKEN, REPO, apiBase), Duration.ofSeconds(2),
				requestTimeout);
	}

	private GitHubIssueSender sender() {
		return sender(apiBase(), Duration.ofSeconds(10));
	}

	private static void assertNoTokenIn(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN);
			assertThat(t.toString()).doesNotContain(TOKEN);
		}
	}

	private static void assertHeader(Headers headers, String name, String value) {
		assertThat(headers.get(name)).as(name).containsExactly(value);
	}

	@Test
	void postsToIssuesWithExactHeaders() throws Exception {
		sender().send(ISSUE);

		assertThat(requests).hasSize(1);
		Captured request = requests.get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/repos/kimdoodoo12/SongRushApp/issues");
		assertHeader(request.headers(), "Authorization", "Bearer " + TOKEN);
		assertHeader(request.headers(), "Accept", "application/vnd.github+json");
		assertHeader(request.headers(), "X-GitHub-Api-Version", "2022-11-28");
		assertHeader(request.headers(), "User-Agent", "SongRushBot/1.0 (+https://animeopguessr.com)");
		assertHeader(request.headers(), "Content-Type", "application/json");
	}

	@Test
	void bodyRoundTrips() throws Exception {
		sender().send(ISSUE);

		JsonNode json = MAPPER.readTree(new String(requests.get(0).body(), StandardCharsets.UTF_8));
		assertThat(json.isObject()).isTrue();
		assertThat(json.path("title").asString(null)).isEqualTo(ISSUE.title());
		assertThat(json.path("body").asString(null)).isEqualTo(ISSUE.body());
		assertThat(json.path("labels").isArray()).isTrue();
		assertThat(json.path("labels").size()).isEqualTo(2);
		assertThat(json.path("labels").get(0).asString(null)).isEqualTo("문의");
		assertThat(json.path("labels").get(1).asString(null)).isEqualTo("bug");
		List<String> keys = new java.util.ArrayList<>();
		json.propertyNames().forEach(keys::add);
		assertThat(keys).containsExactlyInAnyOrder("title", "body", "labels");
	}

	@Test
	void created201IsSuccess() throws Exception {
		status = 201;
		sender().send(ISSUE);
		assertThat(requests).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(ints = { 401, 403, 422, 500 })
	void nonSuccessStatusThrows(int code) {
		status = code;
		IssueSendException error = assertThrows(IssueSendException.class, () -> sender().send(ISSUE));
		assertNoTokenIn(error);
		assertThat(requests).as("exactly one call, no retry").hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(ints = { 401, 403 })
	void rejectedTokenIsLoggedAtError(int code, CapturedOutput output) {
		status = code;
		assertThrows(IssueSendException.class, () -> sender().send(ISSUE));
		String line = "feedback: GitHub rejected the token (status " + code + ") - renew SONGRUSH_FEEDBACK_TOKEN";
		assertThat(output.getAll()).contains(line);
		assertThat(output.getAll().lines().filter(l -> l.contains(line)).findFirst().orElse(""))
				.contains("ERROR");
	}

	@ParameterizedTest
	@ValueSource(ints = { 401, 422, 500 })
	void logsCarryNeitherTokenNorUserText(int code, CapturedOutput output) {
		status = code;
		assertThrows(IssueSendException.class, () -> sender().send(ISSUE));
		assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain("line1").doesNotContain("quoted");
	}

	@Test
	void silentServerFailsWithinTimeout() {
		hang = true;
		GitHubIssueSender sender = sender(apiBase(), Duration.ofMillis(500));
		IssueSendException error = assertTimeoutPreemptively(Duration.ofSeconds(8),
				() -> assertThrows(IssueSendException.class, () -> sender.send(ISSUE)));
		assertNoTokenIn(error);
	}

	@Test
	void refusedConnectionThrows() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();
		}
		GitHubIssueSender sender = sender("http://127.0.0.1:" + port, Duration.ofSeconds(5));
		IssueSendException error = assertTimeoutPreemptively(Duration.ofSeconds(15),
				() -> assertThrows(IssueSendException.class, () -> sender.send(ISSUE)));
		assertNoTokenIn(error);
	}
}
