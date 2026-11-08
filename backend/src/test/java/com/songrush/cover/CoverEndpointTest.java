package com.songrush.cover;

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
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.audio.AudioTokens;

// Cycle 093 (B-020) criteria 3 and 5: GET /cover/{id} in disk mode on a real server, songrush.data-dir
// pointing at a temp folder written before the context loads, in the style of AudioEndpointTest.
// Cover A (anime 1) is only served; cover C (anime 3) has its file deleted by one test, so test order
// does not matter. Cover B's index size differs from its file, so it is never served. The S3 served
// map names cover S, which disk mode must not serve. Theme 10 has an audio file, so a current audio
// token exists. Cycle 095 (C-022): cover P (anime 5) is a PNG, served as image/png; cover W (anime 6)
// names a file with another suffix (.gif, present on disk) and is skipped.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CoverEndpointTest {

	private static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	static final String A = "Qm7Zx2Lp9sVb4nWk1rTy0A";

	static final String B = "Hj3K8dFq5wEr6tYu2iOp9Z";

	static final String C = "Mn1B4vC7xZ0lK3jH6gF9dS";

	static final String S = "Sv4kz_Ru8-wPq2Lm6Nb0Xe";

	static final String P = "Pw8Ly3Nc6Rt1Vb5Zk0Hs4D";

	static final String W = "Wd5Gh2Jk7Lq0Zx3Cv6Bn9M";

	private static final int SIZE_A = 345;

	private static final int SIZE_C = 210;

	private static final int SIZE_P = 278;

	private static final Set<String> ALLOWED_HEADERS = Set.of("content-type", "content-length", "cache-control",
			"date", "keep-alive", "connection");

	private static final String NOT_FOUND = "{\"error\":\"not found\"}";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		try {
			Path audio = Files.createDirectories(dataDir.resolve("audio-cache"));
			Files.copy(FIXTURE_CATALOG, dataDir.resolve("catalog.json"));
			Files.writeString(audio.resolve("index.json"), """
					{
					  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000}
					}
					""", StandardCharsets.UTF_8);
			Files.write(audio.resolve("CowboyBebop-OP1.ogg"), new byte[101]);

			Path covers = Files.createDirectories(dataDir.resolve("cover-cache"));
			Files.write(covers.resolve(A + ".jpg"), CoverMapTest.jpeg(1, SIZE_A));
			Files.write(covers.resolve(B + ".jpg"), CoverMapTest.jpeg(2, 99));
			Files.write(covers.resolve(C + ".jpg"), CoverMapTest.jpeg(3, SIZE_C));
			Files.write(covers.resolve(S + ".jpg"), CoverMapTest.jpeg(4, 50));
			Files.write(covers.resolve(P + ".png"), CoverMapTest.png(5, SIZE_P));
			Files.write(covers.resolve(W + ".gif"), CoverMapTest.png(6, 60));
			Files.writeString(covers.resolve("index.json"), "{\"version\": 1, \"covers\": {"
					+ String.join(", ", CoverMapTest.disk(1, A, SIZE_A), CoverMapTest.disk(2, B, 98),
							CoverMapTest.disk(3, C, SIZE_C), CoverMapTest.diskPng(5, P, SIZE_P),
							CoverMapTest.diskFile(6, W, W + ".gif", 60))
					+ "}}", StandardCharsets.UTF_8);
			Files.writeString(covers.resolve("s3-covers.json"), "{" + CoverMapTest.s3(4, S, 50) + "}",
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AudioTokens tokens;

	@Autowired
	private CoverMap covers;

	private HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static Set<String> headerNames(HttpResponse<?> response) {
		return response.headers().map().keySet().stream()
				.map(name -> name.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());
	}

	private static void assertNotFound(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(NOT_FOUND);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				type -> assertThat(type).startsWith("application/json"));
	}

	@Test
	void theBeanIsTheDiskMap() {
		assertThat(covers.idFor(1)).hasValue(A);
		assertThat(covers.idFor(2)).as("index size differs from the file").isEmpty();
		assertThat(covers.idFor(4)).as("served map is not read in disk mode").isEmpty();
	}

	// Criterion 3
	@Test
	void knownIdServesTheExactFile() throws Exception {
		HttpResponse<byte[]> response = get("/cover/" + A);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(CoverMapTest.jpeg(1, SIZE_A));
		assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
		assertThat(response.headers().firstValue("Content-Length")).hasValue(Integer.toString(SIZE_A));
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
	}

	// Cycle 095 criterion 5: the disk index's PNG entry loads; the other-suffix entry is skipped.
	@Test
	void theBeanHasThePngEntryAndSkipsOtherSuffixes() {
		assertThat(covers.idFor(5)).hasValue(P);
		assertThat(covers.fileFor(P)).isPresent();
		assertThat(covers.idFor(6)).as("file suffix is neither .jpg nor .png").isEmpty();
		assertThat(covers.fileFor(W)).isEmpty();
	}

	// Cycle 095 criterion 5: a PNG cover is served as image/png with its exact bytes.
	@Test
	void pngCoverIsServedAsImagePng() throws Exception {
		HttpResponse<byte[]> response = get("/cover/" + P);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(CoverMapTest.png(5, SIZE_P));
		assertThat(response.headers().firstValue("Content-Type")).hasValue("image/png");
		assertThat(response.headers().firstValue("Content-Length")).hasValue(Integer.toString(SIZE_P));
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
		assertThat(response.headers().firstValue("Last-Modified")).isEmpty();
		assertThat(response.headers().firstValue("ETag")).isEmpty();
		assertThat(headerNames(response)).isSubsetOf(ALLOWED_HEADERS);
	}

	// Cycle 095 criterion 5: an entry whose file has another suffix is never served.
	@Test
	void otherSuffixEntryIs404() throws Exception {
		assertNotFound(get("/cover/" + W));
		assertNotFound(get("/cover/" + P + ".png"));
	}

	// Criterion 3: nothing that lets a cache or a client name or date the file
	@Test
	void responseHeadersAreAllowListed() throws Exception {
		HttpResponse<byte[]> response = get("/cover/" + A);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Last-Modified")).isEmpty();
		assertThat(response.headers().firstValue("ETag")).isEmpty();
		assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
		assertThat(headerNames(response)).isSubsetOf(ALLOWED_HEADERS);
	}

	// Criterion 3: a Range request is ignored and gets the whole file
	@Test
	void rangeRequestGetsTheWholeFile() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cover/" + A))
				.timeout(Duration.ofSeconds(10))
				.header("Range", "bytes=0-9")
				.GET()
				.build();
		HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(CoverMapTest.jpeg(1, SIZE_A));
		assertThat(headerNames(response)).isSubsetOf(ALLOWED_HEADERS);
	}

	// Criterion 3
	@Test
	void unknownIdIs404() throws Exception {
		assertNotFound(get("/cover/Zz9Yy8Xx7Ww6Vv5Uu4Tt3S"));
		assertNotFound(get("/cover/nope"));
	}

	// The id pattern, exact lookups, and no file-name access.
	@Test
	void malformedOrNonExactIdIs404() throws Exception {
		for (String path : List.of("/cover/" + A + ".jpg", "/cover/" + A.toUpperCase(), "/cover/" + A.toLowerCase(),
				"/cover/" + "L".repeat(65), "/cover/index.json", "/cover/index", "/cover/" + A + "%2E", "/cover/")) {
			HttpResponse<byte[]> response = get(path);
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			assertThat(response.body()).as(path).isNotEqualTo(CoverMapTest.jpeg(1, SIZE_A));
		}
		assertNotFound(get("/cover/" + A.toUpperCase()));
		assertNotFound(get("/cover/index"));
	}

	// Criterion 6: an index entry whose file has another size is never served
	@Test
	void wrongSizeEntryIs404() throws Exception {
		assertNotFound(get("/cover/" + B));
	}

	// Disk mode reads only the disk index, never the S3 served map.
	@Test
	void servedMapIdIs404InDiskMode() throws Exception {
		assertNotFound(get("/cover/" + S));
	}

	// A file that cannot be opened at request time is a clean 404.
	@Test
	void deletedFileIs404() throws Exception {
		Files.delete(dataDir.resolve("cover-cache").resolve(C + ".jpg"));
		assertNotFound(get("/cover/" + C));
	}

	// Criterion 5: the two maps share nothing
	@Test
	void audioTokenAtCoverIs404() throws Exception {
		String token = tokens.issue(10, Duration.ofMinutes(1));
		assertThat(get("/audio/" + token).statusCode()).as("the token is current").isEqualTo(200);
		assertNotFound(get("/cover/" + token));
	}

	// Criterion 5
	@Test
	void coverIdAtAudioIs404() throws Exception {
		HttpResponse<byte[]> response = get("/audio/" + A);
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(NOT_FOUND);
	}

	// Logs carry at most the first 6 characters of an id.
	@Test
	void logsCarryNoFullId(CapturedOutput output) throws Exception {
		String unknown = "Ku7Jh6Gf5Dd4Ss3Aa2Pp1O";
		get("/cover/" + unknown);
		get("/cover/" + B);
		get("/cover/" + A);
		get("/cover/" + A.toUpperCase());
		for (String id : List.of(unknown, A, B, A.toUpperCase())) {
			assertThat(output.getAll()).as("log carries " + id).doesNotContain(id.substring(0, 7));
		}
	}

}
