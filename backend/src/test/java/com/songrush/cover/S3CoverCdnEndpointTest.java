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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

// Cycle 106 acceptance 2 (covers): in S3 mode with songrush.audio.cdn-host set, GET /cover/{id}
// answers 302 to the same 60-second presigned URL of c/<id> with scheme https and the CDN host. Set up
// like S3CoverEndpointTest (its fixture constants and fake credentials are reused); nothing touches
// the network and the HTTP client never follows redirects.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3CoverCdnEndpointTest {

	private static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	static final String CDN_HOST = "d1234abcd.cloudfront.net";

	static final String A = CoverMapTest.A;

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void s3ModeWithCdn(DynamicPropertyRegistry registry) {
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
					""".formatted(S3CoverEndpointTest.AUDIO_KEY_10), StandardCharsets.UTF_8);
			Path covers = Files.createDirectories(dataDir.resolve("cover-cache"));
			Files.writeString(covers.resolve("s3-covers.json"), "{" + CoverMapTest.s3(1, A, 40) + "}",
					StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> dataDir.toString());
		registry.add("songrush.audio.bucket", () -> S3CoverEndpointTest.BUCKET);
		registry.add("songrush.audio.cdn-host", () -> CDN_HOST);
	}

	@TestBean(name = "awsCredentialsProvider", methodName = "fakeCredentials")
	private AwsCredentialsProvider credentials;

	static AwsCredentialsProvider fakeCredentials() {
		return S3CoverEndpointTest.fakeCredentials();
	}

	@LocalServerPort
	private int port;

	private URI locationForA() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/cover/" + A))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
		String value = response.headers().firstValue("Location").orElse(null);
		assertThat(value).as("Location header").isNotNull();
		return URI.create(value);
	}

	@Test
	void locationHostIsTheCdnHostOverHttps() throws Exception {
		URI location = locationForA();
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo(CDN_HOST);
		assertThat(location.getPort()).as("no port").isEqualTo(-1);
		assertThat(location.toString()).doesNotContain(S3CoverEndpointTest.HOST);
	}

	@Test
	void locationPathIsStillTheCoverKey() throws Exception {
		assertThat(locationForA().getRawPath()).isEqualTo("/c/" + A);
	}

	@Test
	void locationIsStillAPresignedUrlValidFor60Seconds() throws Exception {
		URI location = locationForA();
		assertThat(location.getRawQuery()).as("query of %s", location).isNotNull();
		List<String> parameters = Arrays.asList(location.getRawQuery().split("&"));
		assertThat(parameters).contains("X-Amz-Expires=60");
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Signature="));
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Credential=" + S3CoverEndpointTest.ACCESS_KEY));
		assertThat(parameters).contains("X-Amz-SignedHeaders=host");
	}

}
