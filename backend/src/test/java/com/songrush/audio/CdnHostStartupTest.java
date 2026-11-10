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
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.songrush.BackendApplication;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

// Cycle 106 acceptance 2 (switch off) and 3. Each case is a real SERVLET application on port 0 in S3
// mode, as in S3StartupTest: the awsCredentialsProvider bean definition is swapped for static fake
// credentials, so nothing touches the network. Without songrush.audio.cdn-host (or with it blank) the
// /audio and /cover redirects keep the S3 host; an invalid non-blank value fails startup.
class CdnHostStartupTest {

	private static final String PROVIDER_BEAN = "awsCredentialsProvider";

	private static final String CDN_HOST = "d1234abcd.cloudfront.net";

	// A cover id of the served cover map (the same value as CoverMapTest.A).
	private static final String COVER_ID = "Qm7Zx2Lp9sVb4nWk1rTy0A";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	Path data;

	@BeforeEach
	void writeFixture() {
		S3Fixture.write(data, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		try {
			Path covers = Files.createDirectories(data.resolve("cover-cache"));
			Files.writeString(covers.resolve("s3-covers.json"), "{\"1\": {\"id\": \"%s\", \"key\": \"c/%s\", \"size\": 40}}"
					.formatted(COVER_ID, COVER_ID), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static ApplicationContextInitializer<ConfigurableApplicationContext> fakeProvider() {
		AwsCredentialsProvider provider = S3Fixture.staticCredentials();
		return context -> context.addBeanFactoryPostProcessor(beanFactory -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			if (registry.containsBeanDefinition(PROVIDER_BEAN)) {
				registry.removeBeanDefinition(PROVIDER_BEAN);
			}
			registry.registerBeanDefinition(PROVIDER_BEAN,
					new RootBeanDefinition(AwsCredentialsProvider.class, () -> provider));
		});
	}

	// cdnHost null means the property is not passed at all.
	private ConfigurableApplicationContext startS3(String cdnHost) {
		List<String> args = new ArrayList<>(List.of("--server.port=0",
				"--spring.main.banner-mode=off",
				"--songrush.data-dir=" + data.toString(),
				"--songrush.audio.bucket=" + S3Fixture.BUCKET));
		if (cdnHost != null) {
			args.add("--songrush.audio.cdn-host=" + cdnHost);
		}
		return new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.initializers(fakeProvider())
				.run(args.toArray(String[]::new));
	}

	private static URI location(ConfigurableApplicationContext context, String path)
			throws IOException, InterruptedException {
		String port = context.getEnvironment().getProperty("local.server.port");
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).as("GET %s", path).isEqualTo(302);
		String value = response.headers().firstValue("Location").orElse(null);
		assertThat(value).as("Location header of %s", path).isNotNull();
		return URI.create(value);
	}

	private static URI audioLocation(ConfigurableApplicationContext context) throws Exception {
		String token = context.getBean(AudioTokens.class).issue(10, Duration.ofMinutes(1));
		return location(context, "/audio/" + token);
	}

	private static URI coverLocation(ConfigurableApplicationContext context) throws Exception {
		return location(context, "/cover/" + COVER_ID);
	}

	private static void assertS3Presigned(URI location, String rawPath) {
		assertThat(location.getScheme()).isEqualTo("https");
		assertThat(location.getHost()).isEqualTo(S3Fixture.HOST);
		assertThat(location.getRawPath()).isEqualTo(rawPath);
		assertThat(location.getRawQuery()).contains("X-Amz-Expires=60").contains("X-Amz-Signature=");
	}

	@Test
	void withoutTheCdnHostTheRedirectsKeepTheS3Host() throws Exception {
		try (ConfigurableApplicationContext context = startS3(null)) {
			assertS3Presigned(audioLocation(context), "/" + S3Fixture.KEY_10);
			assertS3Presigned(coverLocation(context), "/c/" + COVER_ID);
		}
	}

	@Test
	void aBlankCdnHostKeepsTheS3Host() throws Exception {
		try (ConfigurableApplicationContext context = startS3("")) {
			assertS3Presigned(audioLocation(context), "/" + S3Fixture.KEY_10);
			assertS3Presigned(coverLocation(context), "/c/" + COVER_ID);
		}
	}

	// The control case for the invalid-host test: the same fixture with a valid host starts and both
	// redirects use it.
	@Test
	void aValidCdnHostStartsAndIsUsedByBothRedirects() throws Exception {
		try (ConfigurableApplicationContext context = startS3(CDN_HOST)) {
			URI audio = audioLocation(context);
			URI cover = coverLocation(context);
			assertThat(audio.getHost()).isEqualTo(CDN_HOST);
			assertThat(cover.getHost()).isEqualTo(CDN_HOST);
			assertThat(audio.getScheme()).isEqualTo("https");
			assertThat(cover.getScheme()).isEqualTo("https");
		}
	}

	// Acceptance 3
	@Test
	void anInvalidCdnHostFailsStartup() {
		ConfigurableApplicationContext context = null;
		Throwable failure = null;
		try {
			context = startS3("https://bad");
		}
		catch (RuntimeException e) {
			failure = e;
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
		assertThat(failure).as("S3-mode startup with songrush.audio.cdn-host=https://bad").isNotNull();
	}

}
