package com.songrush.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Cycle 054 (B-009) criterion 2: the built frontend is served from spring.web.resources.static-locations
// (<tmp>/web), with /r/{code} forwarded to index.html. The data folder is the sibling <tmp>/data. A decoy
// <tmp>/catalog.json next to the web folder must never be reachable by path traversal (spec review M2);
// those requests go raw over a Socket so no client normalizes the path.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticFrontendTest {

	private static final String INDEX_MARK = "SONGRUSH-INDEX";

	private static final String DECOY = "DECOY-SECRET";

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void folders(DynamicPropertyRegistry registry) {
		Path data = tmp.resolve("data");
		Path web = tmp.resolve("web");
		try {
			WebFixture.writeData(data);
			Files.createDirectories(web.resolve("assets"));
			Files.writeString(web.resolve("index.html"),
					"<!doctype html><html><body>" + INDEX_MARK + "</body></html>", StandardCharsets.UTF_8);
			Files.writeString(web.resolve("assets").resolve("app.js"), "console.log('app');", StandardCharsets.UTF_8);
			Files.writeString(tmp.resolve("catalog.json"), "{\"secret\":\"" + DECOY + "\"}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> data.toString());
		registry.add("spring.web.resources.static-locations", () -> web.toUri().toString());
	}

	@LocalServerPort
	private int port;

	private HttpResponse<String> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void rootAndRoomPathsServeTheIndex() throws Exception {
		for (String path : List.of("/", "/r/ABCDE", "/r/ABCDE/")) {
			HttpResponse<String> response = get(path);
			assertThat(response.statusCode()).as("status of %s", path).isEqualTo(200);
			assertThat(response.body()).as("body of %s", path).contains(INDEX_MARK);
		}
	}

	@Test
	void assetsAreServedWithTheirContentType() throws Exception {
		HttpResponse<String> response = get("/assets/app.js");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(ct -> assertThat(ct).contains("javascript"));
	}

	@Test
	void traversalNeverReachesTheDecoy() {
		for (String path : List.of("/../catalog.json", "/%2e%2e/catalog.json", "/assets/..%2f..%2fcatalog.json")) {
			WebFixture.RawResponse response = WebFixture.rawGet(port, path);
			assertThat(response.status()).as("status of %s", path).isNotEqualTo(200);
			assertThat(response.text()).as("response to %s", path).doesNotContain(DECOY);
		}
	}

	@Test
	void healthStillAnswers() throws Exception {
		HttpResponse<String> response = get("/health");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("{\"ok\":true}");
	}

	@Test
	void unknownPathIs404() throws Exception {
		assertThat(get("/nope").statusCode()).isEqualTo(404);
	}

}
