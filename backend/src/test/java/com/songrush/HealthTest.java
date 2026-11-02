package com.songrush;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

// Cycle 012 criteria 1-3 and 5: real server on a random port, plain java.net.http client
// (does not throw on 4xx and needs no Boot 4 test-client module).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthTest {

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@LocalServerPort
	private int port;

	private HttpResponse<String> send(String method, String path, String accept)
			throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.method(method, HttpRequest.BodyPublishers.noBody());
		if (accept != null) {
			builder.header("Accept", accept);
		}
		return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static void assertJson(HttpResponse<String> response, int status, String body) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
		assertThat(response.body()).isEqualTo(body);
	}

	// Criterion 1
	@Test
	void healthReturnsOkJson() throws Exception {
		assertJson(send("GET", "/health", null), 200, "{\"ok\":true}");
	}

	// Criterion 2: no Accept header
	@Test
	void unknownPathReturns404Json() throws Exception {
		assertJson(send("GET", "/nope", null), 404, "{\"error\":\"not found\"}");
	}

	// Criterion 2: browser case, where Boot would otherwise render Whitelabel HTML
	@Test
	void unknownPathReturns404JsonEvenForHtmlAccept() throws Exception {
		assertJson(send("GET", "/nope", "text/html"), 404, "{\"error\":\"not found\"}");
	}

	// Criterion 3: no Accept header
	@Test
	void postHealthReturns405Json() throws Exception {
		assertJson(send("POST", "/health", null), 405, "{\"error\":\"method not allowed\"}");
	}

	// Criterion 3: browser Accept header
	@Test
	void postHealthReturns405JsonEvenForHtmlAccept() throws Exception {
		assertJson(send("POST", "/health", "text/html"), 405, "{\"error\":\"method not allowed\"}");
	}
}
