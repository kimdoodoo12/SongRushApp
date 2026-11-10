package com.songrush.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

// Cycle 106 acceptance 2 (audio): in S3 mode with songrush.audio.cdn-host set, GET /audio/{token}
// answers 302 to the same 60-second presigned URL with scheme https and the CDN host; the key path and
// every X-Amz-* parameter stay. Set up like S3AudioEndpointTest: fake static credentials, no network
// (presigning is local), and the HTTP client never follows redirects.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3AudioCdnEndpointTest {

	static final String CDN_HOST = "d1234abcd.cloudfront.net";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void s3ModeWithCdn(DynamicPropertyRegistry registry) {
		S3Fixture.write(dataDir, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		registry.add("songrush.data-dir", () -> dataDir.toString());
		registry.add("songrush.audio.bucket", () -> S3Fixture.BUCKET);
		registry.add("songrush.audio.cdn-host", () -> CDN_HOST);
	}

	@TestBean(name = "awsCredentialsProvider", methodName = "fakeCredentials")
	private AwsCredentialsProvider credentials;

	static AwsCredentialsProvider fakeCredentials() {
		return S3Fixture.staticCredentials();
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AudioTokens tokens;

	private URI locationFor10() throws IOException, InterruptedException {
		String token = tokens.issue(10, Duration.ofMinutes(1));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audio/" + token))
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
		URI location = locationFor10();
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo(CDN_HOST);
		assertThat(location.getPort()).as("no port").isEqualTo(-1);
		assertThat(location.toString()).doesNotContain(S3Fixture.HOST);
	}

	@Test
	void locationPathIsStillTheMappedKey() throws Exception {
		assertThat(locationFor10().getRawPath()).isEqualTo("/" + S3Fixture.KEY_10);
	}

	@Test
	void locationIsStillAPresignedUrlValidFor60Seconds() throws Exception {
		URI location = locationFor10();
		assertThat(location.getRawQuery()).as("query of %s", location).isNotNull();
		List<String> parameters = Arrays.asList(location.getRawQuery().split("&"));
		assertThat(parameters).contains("X-Amz-Expires=60");
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Signature="));
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Credential=" + S3Fixture.ACCESS_KEY));
		assertThat(parameters).contains("X-Amz-SignedHeaders=host");
	}

}
