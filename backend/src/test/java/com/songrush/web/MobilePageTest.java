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

// Cycle 154 criterion 2 (D-140): the Flutter mobile web build is served from <web>/m/. /m redirects to
// /m/; /m/ and /m/r/{code}, with or without a trailing slash, serve /m/index.html (not the React
// index.html), and the build's own files come from the same folder with the no-cache header the image
// sets (criterion 5). The React paths keep serving the React index.html.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MobilePageTest {

	private static final String INDEX_MARK = "SONGRUSH-INDEX";

	private static final String MOBILE_MARK = "SONGRUSH-MOBILE";

	// Never follows redirects, so the /m redirect itself is checked.
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
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
			Files.createDirectories(web.resolve("m"));
			Files.writeString(web.resolve("index.html"),
					"<!doctype html><html><body>" + INDEX_MARK + "</body></html>", StandardCharsets.UTF_8);
			Files.writeString(web.resolve("m").resolve("index.html"),
					"<!doctype html><html><head><base href=\"/m/\"></head><body>" + MOBILE_MARK + "</body></html>",
					StandardCharsets.UTF_8);
			Files.writeString(web.resolve("m").resolve("main.dart.js"), "console.log('m');", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> data.toString());
		registry.add("spring.web.resources.static-locations", () -> web.toUri().toString());
		registry.add("spring.web.resources.cache.cachecontrol.no-cache", () -> "true");
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
	void bareMRedirectsToTheSlash() throws Exception {
		HttpResponse<String> response = get("/m");
		assertThat(response.statusCode()).isBetween(301, 308);
		assertThat(response.headers().firstValue("Location"))
				.hasValueSatisfying(location -> assertThat(location).endsWith("/m/"));
	}

	@Test
	void mobilePathsServeTheMobileIndex() throws Exception {
		for (String path : List.of("/m/", "/m/r/ABCDE", "/m/r/ABCDE/")) {
			HttpResponse<String> response = get(path);
			assertThat(response.statusCode()).as("status of %s", path).isEqualTo(200);
			assertThat(response.body()).as("body of %s", path).contains(MOBILE_MARK).doesNotContain(INDEX_MARK);
		}
	}

	@Test
	void mobileFilesAreServedWithNoCache() throws Exception {
		HttpResponse<String> response = get("/m/main.dart.js");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type"))
				.hasValueSatisfying(ct -> assertThat(ct).contains("javascript"));
		assertThat(response.headers().firstValue("Cache-Control"))
				.hasValueSatisfying(value -> assertThat(value).contains("no-cache"));
	}

	@Test
	void reactPathsStillServeTheReactIndex() throws Exception {
		for (String path : List.of("/", "/r/ABCDE")) {
			HttpResponse<String> response = get(path);
			assertThat(response.statusCode()).as("status of %s", path).isEqualTo(200);
			assertThat(response.body()).as("body of %s", path).contains(INDEX_MARK).doesNotContain(MOBILE_MARK);
		}
	}

}
