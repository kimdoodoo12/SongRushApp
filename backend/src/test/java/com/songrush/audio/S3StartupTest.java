package com.songrush.audio;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import com.songrush.BackendApplication;
import com.songrush.catalog.CatalogException;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

// Cycle 074 criteria 12 (503, review M1), 13 and the blank-bucket rule (review M5, H2, H3). Each case
// is a real SERVLET application on port 0, as in StaticGuardTest. In S3 mode the
// awsCredentialsProvider bean definition is swapped for a test provider by a BeanFactoryPostProcessor
// that runs after the configuration classes are parsed, so DefaultCredentialsProvider (and its
// instance-metadata lookup) is never created and nothing touches the network.
class S3StartupTest {

	private static final String PROVIDER_BEAN = "awsCredentialsProvider";

	private static final HttpClient CLIENT = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	Path data;

	// Replaces the provider bean definition (or adds it when absent) with the given instance.
	private static ApplicationContextInitializer<ConfigurableApplicationContext> provider(
			AwsCredentialsProvider provider) {
		return context -> context.addBeanFactoryPostProcessor(beanFactory -> {
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			if (registry.containsBeanDefinition(PROVIDER_BEAN)) {
				registry.removeBeanDefinition(PROVIDER_BEAN);
			}
			registry.registerBeanDefinition(PROVIDER_BEAN,
					new RootBeanDefinition(AwsCredentialsProvider.class, () -> provider));
		});
	}

	private ConfigurableApplicationContext startS3(AwsCredentialsProvider provider) {
		return new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.initializers(provider(provider))
				.run("--server.port=0",
						"--spring.main.banner-mode=off",
						"--songrush.data-dir=" + data.toString(),
						"--songrush.audio.bucket=" + S3Fixture.BUCKET);
	}

	// Returns the startup failure, or null when it started (the context is then closed).
	private Throwable startS3Failure(AwsCredentialsProvider provider) {
		ConfigurableApplicationContext context = null;
		try {
			context = startS3(provider);
			return null;
		}
		catch (RuntimeException e) {
			return e;
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
	}

	private static boolean chainContains(Throwable failure, Class<? extends Throwable> type) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static boolean chainMentionsCredentials(Throwable failure, Throwable thrown) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			if (t == thrown) {
				return true;
			}
			String message = t.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains("credential")) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private void assertCatalogFailure(String what) {
		Throwable failure = startS3Failure(S3Fixture.staticCredentials());
		assertThat(failure).as("S3-mode startup with %s", what).isNotNull();
		assertThat(chainContains(failure, CatalogException.class)).as("cause chain of %s has a CatalogException",
				failure).isTrue();
	}

	private static HttpResponse<byte[]> get(ConfigurableApplicationContext context, String token)
			throws IOException, InterruptedException {
		String port = context.getEnvironment().getProperty("local.server.port");
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/audio/" + token))
				.timeout(Duration.ofSeconds(10))
				.GET()
				.build();
		return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	// The control case: the full fixture with static credentials starts.
	@Test
	void s3ModeWithAValidFixtureStarts() {
		S3Fixture.write(data, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		assertThat(startS3Failure(S3Fixture.staticCredentials())).as("S3-mode startup").isNull();
	}

	// Criterion 13
	@Test
	void missingServedMapFailsStartup() {
		S3Fixture.write(data, S3Fixture.INDEX, null);
		assertCatalogFailure("no s3-keys.json");
	}

	// Review H2
	@Test
	void missingIndexFailsStartup() {
		S3Fixture.write(data, null, S3Fixture.SERVED_MAP);
		assertCatalogFailure("no index.json");
	}

	@Test
	void zeroCandidatesFailsStartup() {
		S3Fixture.write(data, S3Fixture.INDEX, S3Fixture.SERVED_MAP_NO_MATCH);
		assertCatalogFailure("no matching served-map entry");
	}

	@Test
	void invalidServedMapFailsStartup() {
		S3Fixture.write(data, S3Fixture.INDEX, "{ not json");
		assertCatalogFailure("an invalid s3-keys.json");
	}

	// Criterion 13 and review H3: the one-time resolveCredentials() at startup throws.
	@Test
	void throwingCredentialsProviderFailsStartup() {
		S3Fixture.write(data, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		SdkClientException thrown = SdkClientException.create("test: no credentials available");
		AtomicInteger calls = new AtomicInteger();
		AwsCredentialsProvider throwing = () -> {
			calls.incrementAndGet();
			throw thrown;
		};
		Throwable failure = startS3Failure(throwing);
		assertThat(failure).as("S3-mode startup with a throwing provider").isNotNull();
		assertThat(calls.get()).as("resolveCredentials() calls").isGreaterThanOrEqualTo(1);
		assertThat(chainMentionsCredentials(failure, thrown))
				.as("cause chain of %s carries the provider failure or mentions credentials", failure).isTrue();
	}

	// Review M1: the startup check succeeds, every later resolveCredentials() throws, so presigning a
	// valid token fails and the answer is 503 without a Location.
	@Test
	void presignFailureIs503WithoutLocation() throws Exception {
		S3Fixture.write(data, S3Fixture.INDEX, S3Fixture.SERVED_MAP);
		AwsCredentialsProvider good = S3Fixture.staticCredentials();
		AtomicInteger calls = new AtomicInteger();
		AwsCredentialsProvider firstOnly = () -> {
			if (calls.getAndIncrement() == 0) {
				return good.resolveCredentials();
			}
			throw SdkClientException.create("test: credentials expired");
		};
		try (ConfigurableApplicationContext context = startS3(firstOnly)) {
			assertThat(calls.get()).as("resolveCredentials() calls at startup").isEqualTo(1);
			String token = context.getBean(AudioTokens.class).issue(10, Duration.ofMinutes(1));
			HttpResponse<byte[]> response = get(context, token);
			assertThat(response.statusCode()).isEqualTo(503);
			assertThat(response.headers().firstValue("Location")).isEmpty();
			String all = response.headers().map().toString() + new String(response.body(), StandardCharsets.UTF_8);
			for (String revealing : S3Fixture.REVEALING) {
				assertThat(all).doesNotContainIgnoringCase(revealing);
			}
			assertThat(all).doesNotContain(S3Fixture.KEY_10);
		}
	}

	// Review M5: a blank bucket is disk mode. The file is streamed from the cache folder, there is no
	// served map, and no credentials provider bean exists.
	@Test
	void blankBucketIsDiskMode() throws Exception {
		S3Fixture.write(data, """
				{"10": {"basename": "CowboyBebop-OP1.ogg", "size": 5, "durationMs": 89000}}
				""", null);
		byte[] audio = "OggS!".getBytes(StandardCharsets.US_ASCII);
		Files.write(data.resolve("audio-cache").resolve("CowboyBebop-OP1.ogg"), audio);
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
				.web(WebApplicationType.SERVLET)
				.run("--server.port=0",
						"--spring.main.banner-mode=off",
						"--songrush.data-dir=" + data.toString(),
						"--songrush.audio.bucket=")) {
			assertThat(context.getBeansOfType(AwsCredentialsProvider.class)).isEmpty();
			String token = context.getBean(AudioTokens.class).issue(10, Duration.ofMinutes(1));
			HttpResponse<byte[]> response = get(context, token);
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).isEqualTo(audio);
			assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/ogg");
			assertThat(response.headers().firstValue("Location")).isEmpty();
		}
	}

}
