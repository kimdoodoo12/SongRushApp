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
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.songrush.audio.AudioTokens;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

// Cycle 093 (B-020) criteria 4 and 5: in S3 mode GET /cover/{id} answers 302 to a 60-second
// presigned GetObject URL of key c/<id> in the audio bucket, in the style of S3AudioEndpointTest
// (whose package-private S3Fixture is copied in part here). The credentials provider is replaced by
// static fake credentials, so nothing touches the network (presigning is local). The HTTP client
// never follows redirects. The data dir also holds a valid disk cover index and file for cover D,
// which S3 mode must not serve.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3CoverEndpointTest {

	private static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	static final String BUCKET = "test-bucket";

	static final String HOST = "test-bucket.s3.ap-southeast-2.amazonaws.com";

	static final String AUDIO_KEY_10 = "a/Q7mZ2xLp9sVb4nWk1rTy0A";

	static final String ACCESS_KEY = "AKIDFAKETEST0000000";

	static final String SECRET_KEY = "fakeSecretKeyForTestsOnly0000000000000000";

	static final String A = CoverMapTest.A;

	static final String B = CoverMapTest.B;

	// Only in the disk index (with its file).
	static final String D = CoverMapTest.D;

	private static final String NOT_FOUND = "{\"error\":\"not found\"}";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void s3Mode(DynamicPropertyRegistry registry) {
		try {
			Path audio = Files.createDirectories(dataDir.resolve("audio-cache"));
			Files.copy(FIXTURE_CATALOG, dataDir.resolve("catalog.json"));
			Files.writeString(audio.resolve("index.json"), """
					{
					  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000}
					}
					""", StandardCharsets.UTF_8);
			Files.writeString(audio.resolve("s3-keys.json"), """
					{
					  "CowboyBebop-OP1.ogg": {"key": "%s", "size": 101}
					}
					""".formatted(AUDIO_KEY_10), StandardCharsets.UTF_8);

			Path covers = Files.createDirectories(dataDir.resolve("cover-cache"));
			// B's key is not c/<id>: a bad entry, skipped.
			Files.writeString(covers.resolve("s3-covers.json"), "{" + CoverMapTest.s3(1, A, 40) + ", "
					+ "\"2\": {\"id\": \"" + B + "\", \"key\": \"a/" + B + "\", \"size\": 40}" + "}",
					StandardCharsets.UTF_8);
			Files.write(covers.resolve(D + ".jpg"), CoverMapTest.jpeg(4, 30));
			Files.writeString(covers.resolve("index.json"),
					"{\"version\": 1, \"covers\": {" + CoverMapTest.disk(3, D, 30) + "}}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> dataDir.toString());
		registry.add("songrush.audio.bucket", () -> BUCKET);
	}

	@TestBean(name = "awsCredentialsProvider", methodName = "fakeCredentials")
	private AwsCredentialsProvider credentials;

	static AwsCredentialsProvider fakeCredentials() {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
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

	private HttpResponse<byte[]> redirectForA() throws IOException, InterruptedException {
		HttpResponse<byte[]> response = get("/cover/" + A);
		assertThat(response.statusCode()).isEqualTo(302);
		return response;
	}

	private static URI location(HttpResponse<?> response) {
		String value = response.headers().firstValue("Location").orElse(null);
		assertThat(value).as("Location header").isNotNull();
		return URI.create(value);
	}

	private static List<String> queryParameters(URI uri) {
		assertThat(uri.getRawQuery()).as("query of %s", uri).isNotNull();
		return Arrays.asList(uri.getRawQuery().split("&"));
	}

	private static void assertNotFound(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(NOT_FOUND);
		assertThat(response.headers().firstValue("Location")).isEmpty();
	}

	@Test
	void theBeanIsTheServedMap() {
		assertThat(covers.idFor(1)).hasValue(A);
		assertThat(covers.keyFor(A)).hasValue("c/" + A);
		assertThat(covers.idFor(2)).as("bad key").isEmpty();
		assertThat(covers.idFor(3)).as("the disk index is not read in S3 mode").isEmpty();
		assertThat(covers.size()).isEqualTo(1);
	}

	// Criterion 4
	@Test
	void knownIdRedirectsWithAnEmptyBody() throws Exception {
		HttpResponse<byte[]> response = redirectForA();
		assertThat(response.body()).isEmpty();
	}

	// Criterion 4
	@Test
	void locationHostIsTheVirtualHostedAudioBucket() throws Exception {
		URI location = location(redirectForA());
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo(HOST);
	}

	// Criterion 4
	@Test
	void locationPathIsTheCoverKey() throws Exception {
		assertThat(location(redirectForA()).getRawPath()).isEqualTo("/c/" + A);
	}

	// Criterion 4
	@Test
	void locationIsAPresignedUrlValidFor60Seconds() throws Exception {
		List<String> parameters = queryParameters(location(redirectForA()));
		assertThat(parameters).contains("X-Amz-Expires=60");
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Signature="));
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Credential=" + ACCESS_KEY));
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Credential=") && p.contains("ap-southeast-2"));
	}

	// Criterion 4
	@Test
	void responseIsNotCached() throws Exception {
		HttpResponse<byte[]> response = redirectForA();
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
		assertThat(response.headers().firstValue("Last-Modified")).isEmpty();
		assertThat(response.headers().firstValue("ETag")).isEmpty();
		assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
	}

	// Criterion 4
	@Test
	void unknownIdIs404() throws Exception {
		assertNotFound(get("/cover/Zz9Yy8Xx7Ww6Vv5Uu4Tt3S"));
		assertNotFound(get("/cover/nope"));
		assertNotFound(get("/cover/" + A.toUpperCase()));
		assertNotFound(get("/cover/" + A + ".jpg"));
		assertNotFound(get("/cover/" + "L".repeat(65)));
	}

	// Criterion 6: a bad entry is skipped; S3 mode never serves the disk index
	@Test
	void skippedAndDiskOnlyIdsAre404() throws Exception {
		assertNotFound(get("/cover/" + B));
		assertNotFound(get("/cover/" + D));
	}

	// Criterion 5
	@Test
	void audioTokenAtCoverIs404() throws Exception {
		String token = tokens.issue(10, Duration.ofMinutes(1));
		assertThat(get("/audio/" + token).statusCode()).as("the token is current").isEqualTo(302);
		assertNotFound(get("/cover/" + token));
	}

	// Criterion 5
	@Test
	void coverIdAtAudioIs404() throws Exception {
		assertNotFound(get("/audio/" + A));
	}

}
