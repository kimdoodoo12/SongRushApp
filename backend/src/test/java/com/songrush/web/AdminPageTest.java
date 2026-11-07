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

// Cycle 083 backend item 6 (D-097): /admin and /admin/ serve the SPA index.html, the same way /r/{code}
// does (RoomPageController), with no key needed: nginx Basic Auth guards the page. Over a real Tomcat it
// also checks that the admin key is enforced on the matched handler, not on the raw URI string
// (083 backend item 1): path-parameter variants such as /admin;x/api/feedback reach the same handler
// and must still answer 404 without the key. Raw requests go over a Socket so no client normalizes them.
//
// Production API this test relies on: properties songrush.admin.key and songrush.feedback.store-dir;
// GET /admin/api/feedback with X-Admin-Key answers 200 {"items":[...]} with Cache-Control no-store.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminPageTest {

	private static final String INDEX_MARK = "SONGRUSH-INDEX";

	private static final String KEY = "adm1n-key-web-test";

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
		Path feedback = tmp.resolve("feedback");
		try {
			WebFixture.writeData(data);
			Files.createDirectories(web);
			Files.writeString(web.resolve("index.html"),
					"<!doctype html><html><body>" + INDEX_MARK + "</body></html>", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> data.toString());
		registry.add("spring.web.resources.static-locations", () -> web.toUri().toString());
		registry.add("songrush.feedback.store-dir", () -> feedback.toString());
		registry.add("songrush.admin.key", () -> KEY);
	}

	@LocalServerPort
	private int port;

	private HttpResponse<String> get(String path, String key) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET();
		if (key != null) {
			request.header("X-Admin-Key", key);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void adminPathsServeTheIndex() throws Exception {
		for (String path : List.of("/admin", "/admin/")) {
			HttpResponse<String> response = get(path, null);
			assertThat(response.statusCode()).as("status of %s", path).isEqualTo(200);
			assertThat(response.body()).as("body of %s", path).contains(INDEX_MARK);
		}
	}

	@Test
	void adminApiAnswersWithTheKey() throws Exception {
		HttpResponse<String> response = get("/admin/api/feedback", KEY);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"items\"");
		assertThat(response.headers().firstValue("Cache-Control"))
				.hasValueSatisfying(value -> assertThat(value).contains("no-store"));
	}

	@Test
	void adminApiWithoutTheKeyIs404() throws Exception {
		HttpResponse<String> response = get("/admin/api/feedback", null);
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).doesNotContain("\"items\"");
	}

	@Test
	void pathVariantsWithoutTheKeyNeverReachTheList() {
		for (String path : List.of("/admin;x/api/feedback", "/admin/api;x/feedback", "/admin/api/feedback;x",
				"/admin;x/api;y/feedback;z?status=all", "/admin/./api/feedback", "/x/../admin/api/feedback",
				"/admin//api/feedback")) {
			WebFixture.RawResponse response = WebFixture.rawGet(port, path);
			assertThat(response.status()).as("status of %s", path).isNotEqualTo(200);
			assertThat(response.text()).as("response to %s", path).doesNotContain("\"items\"");
		}
	}

	@Test
	void semicolonVariantWithAWrongKeyIs404() {
		WebFixture.RawResponse response = WebFixture.raw(port, "GET /admin;x/api/feedback HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "X-Admin-Key: wrong\r\n"
				+ "Connection: close\r\n"
				+ "\r\n");
		assertThat(response.status()).isEqualTo(404);
		assertThat(response.text()).doesNotContain("\"items\"");
	}

}
