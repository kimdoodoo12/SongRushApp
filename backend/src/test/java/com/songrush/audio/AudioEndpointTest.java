package com.songrush.audio;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Cycle 033 criteria 2-4: GET /audio/{token} on a real server, songrush.data-dir pointing at a
// temp folder written before the context loads (spec review H1). Theme 10's file is only served,
// theme 11's file is deleted by one test, so test order does not matter. Theme 30 is outside the
// draw set but has an index entry and a file of the right size, so only a candidate lookup 404s it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AudioEndpointTest {

	private static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	private static final Set<String> ALLOWED_HEADERS = Set.of("content-type", "content-length", "cache-control",
			"date", "keep-alive", "connection");

	// Basenames and anime slugs of the fixture; numeric ids are not searched (spec review B1).
	private static final List<String> REVEALING = List.of("CowboyBebop-OP1.ogg", "CowboyBebop-ED1.ogg",
			"DeathNote-OP1.ogg", "ObscureOVA-OP1.ogg", "cowboybebop", "deathnote", "obscureova");

	private static final String NOT_FOUND = "{\"error\":\"not found\"}";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		try {
			Path cache = dataDir.resolve("audio-cache");
			Files.createDirectories(cache);
			Files.copy(FIXTURE_CATALOG, dataDir.resolve("catalog.json"));
			Files.writeString(cache.resolve("index.json"), """
					{
					  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000},
					  "11": {"basename": "CowboyBebop-ED1.ogg", "size": 102, "durationMs": 90000},
					  "30": {"basename": "ObscureOVA-OP1.ogg", "size": 104, "durationMs": 85000}
					}
					""", StandardCharsets.UTF_8);
			Files.write(cache.resolve("CowboyBebop-OP1.ogg"), audioBytes(10, 101));
			Files.write(cache.resolve("CowboyBebop-ED1.ogg"), audioBytes(11, 102));
			Files.write(cache.resolve("ObscureOVA-OP1.ogg"), audioBytes(30, 104));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	// Distinctive content per theme: an "OggS" header, then a pattern seeded by the theme id.
	private static byte[] audioBytes(int themeId, int size) {
		byte[] bytes = new byte[size];
		byte[] magic = "OggS".getBytes(StandardCharsets.US_ASCII);
		for (int i = 0; i < size; i++) {
			bytes[i] = i < magic.length ? magic[i] : (byte) (i * 7 + themeId * 31);
		}
		return bytes;
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AudioTokens tokens;

	private HttpResponse<byte[]> get(String token, String range) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audio/" + token))
				.timeout(Duration.ofSeconds(10))
				.GET();
		if (range != null) {
			builder.header("Range", range);
		}
		return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static Set<String> headerNames(HttpResponse<?> response) {
		return response.headers().map().keySet().stream()
				.map(name -> name.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
	}

	private static void assertNoRevealingHeaderValue(HttpResponse<?> response) {
		for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
			for (String value : header.getValue()) {
				for (String revealing : REVEALING) {
					assertThat(value).as("header %s", header.getKey()).doesNotContainIgnoringCase(revealing);
				}
			}
		}
	}

	private static void assertServesTheme10(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(audioBytes(10, 101));
		assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/ogg");
		assertThat(response.headers().firstValue("Content-Length")).hasValue("101");
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
	}

	private static void assertNotFound(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(NOT_FOUND);
		assertNoRevealingHeaderValue(response);
	}

	// Criterion 2
	@Test
	void validTokenServesTheExactFile() throws Exception {
		assertServesTheme10(get(tokens.issue(10, Duration.ofMinutes(1)), null));
	}

	// Criterion 3: header-name allow-list and no basename or slug in any header value
	@Test
	void responseHeadersRevealNothing() throws Exception {
		HttpResponse<byte[]> response = get(tokens.issue(10, Duration.ofMinutes(1)), null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(headerNames(response)).isSubsetOf(ALLOWED_HEADERS);
		assertNoRevealingHeaderValue(response);
	}

	// Criterion 3: a Range request is ignored and gets the whole file
	@Test
	void rangeRequestGetsTheWholeFile() throws Exception {
		HttpResponse<byte[]> response = get(tokens.issue(10, Duration.ofMinutes(1)), "bytes=0-9");
		assertServesTheme10(response);
		assertThat(headerNames(response)).isSubsetOf(ALLOWED_HEADERS);
		assertNoRevealingHeaderValue(response);
	}

	// Criterion 4
	@Test
	void unknownTokenIs404() throws Exception {
		assertNotFound(get("AAAAAAAAAAAAAAAAAAAAAA", null));
		assertNotFound(get("nope", null));
	}

	// Criterion 4
	@Test
	void expiredTokenIs404() throws Exception {
		String token = tokens.issue(10, Duration.ofMillis(1));
		Thread.sleep(50);
		assertNotFound(get(token, null));
	}

	// Criterion 4: theme 11 is a candidate at startup, then its file disappears
	@Test
	void deletedFileIs404() throws Exception {
		Files.delete(dataDir.resolve("audio-cache").resolve("CowboyBebop-ED1.ogg"));
		assertNotFound(get(tokens.issue(11, Duration.ofMinutes(1)), null));
	}

	// Criterion 4: theme 30 is outside the draw set (file present); theme 20 has no cached file
	@Test
	void nonCandidateThemeIs404() throws Exception {
		assertNotFound(get(tokens.issue(30, Duration.ofMinutes(1)), null));
		assertNotFound(get(tokens.issue(20, Duration.ofMinutes(1)), null));
	}

}
