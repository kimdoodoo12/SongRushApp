package com.songrush.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

// Cycle 074 criterion 12 (as revised): in S3 mode GET /audio/{token} answers 302 to a 60-second
// presigned GetObject URL for the mapped key. The data dir holds the fixture catalog, index and
// served map but no audio file. The credentials provider is replaced by static fake credentials,
// so nothing touches the network (presigning is local). The HTTP client never follows redirects.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3AudioEndpointTest {

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
		S3Fixture.write(dataDir, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		registry.add("songrush.data-dir", () -> dataDir.toString());
		registry.add("songrush.audio.bucket", () -> S3Fixture.BUCKET);
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

	private HttpResponse<byte[]> get(String token) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audio/" + token))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private HttpResponse<byte[]> redirectFor10() throws IOException, InterruptedException {
		HttpResponse<byte[]> response = get(tokens.issue(10, Duration.ofMinutes(1)));
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

	private static void assertNoRevealingText(String text, String what) {
		String decoded = URLDecoder.decode(text, StandardCharsets.UTF_8);
		for (String revealing : S3Fixture.REVEALING) {
			assertThat(text).as(what).doesNotContainIgnoringCase(revealing);
			assertThat(decoded).as(what + " (decoded)").doesNotContainIgnoringCase(revealing);
		}
	}

	private static void assertNoRevealingHeaderValue(HttpResponse<?> response) {
		for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
			for (String value : header.getValue()) {
				assertNoRevealingText(value, "header " + header.getKey());
			}
		}
	}

	private static void assertNotFound(HttpResponse<byte[]> response) {
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(NOT_FOUND);
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertNoRevealingHeaderValue(response);
	}

	@Test
	void validTokenRedirects() throws Exception {
		HttpResponse<byte[]> response = redirectFor10();
		assertThat(response.body()).isEmpty();
	}

	@Test
	void locationHostIsTheVirtualHostedBucket() throws Exception {
		URI location = location(redirectFor10());
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo(S3Fixture.HOST);
	}

	@Test
	void locationPathIsTheMappedKey() throws Exception {
		URI location = location(redirectFor10());
		assertThat(location.getRawPath()).isEqualTo("/" + S3Fixture.KEY_10);
	}

	@Test
	void locationIsAPresignedUrlValidFor60Seconds() throws Exception {
		List<String> parameters = queryParameters(location(redirectFor10()));
		assertThat(parameters).contains("X-Amz-Expires=60");
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Signature="));
		assertThat(parameters).anyMatch(p -> p.startsWith("X-Amz-Credential="));
	}

	@Test
	void responseIsNotCached() throws Exception {
		assertThat(redirectFor10().headers().firstValue("Cache-Control")).hasValue("no-store");
	}

	@Test
	void locationAndHeadersRevealNothing() throws Exception {
		HttpResponse<byte[]> response = redirectFor10();
		assertNoRevealingText(location(response).toString(), "Location");
		assertNoRevealingHeaderValue(response);
	}

	@Test
	void unknownTokenIs404() throws Exception {
		assertNotFound(get("AAAAAAAAAAAAAAAAAAAAAA"));
		assertNotFound(get("nope"));
	}

	@Test
	void expiredTokenIs404() throws Exception {
		String token = tokens.issue(10, Duration.ofMillis(1));
		Thread.sleep(50);
		assertNotFound(get(token));
	}

	// Theme 11's served-map size differs from its index size; theme 30 is outside the draw set;
	// theme 20 has no index entry.
	@Test
	void nonCandidateThemeIs404() throws Exception {
		assertNotFound(get(tokens.issue(11, Duration.ofMinutes(1))));
		assertNotFound(get(tokens.issue(30, Duration.ofMinutes(1))));
		assertNotFound(get(tokens.issue(20, Duration.ofMinutes(1))));
	}

}
