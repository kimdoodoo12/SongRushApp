package com.songrush.audio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

// Cycle 074: data dirs for the S3-mode tests. The fixture catalog's draw set is [20, 10, 11].
// In the served map theme 10 matches its index size (the only candidate), theme 11's size differs,
// and theme 30 is outside the draw set. No audio file is written: S3 mode needs none.
final class S3Fixture {

	static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	static final String BUCKET = "test-bucket";

	static final String HOST = "test-bucket.s3.ap-southeast-2.amazonaws.com";

	static final String KEY_10 = "a/Q7mZ2xLp9sVb4nWk1rTy0A";

	static final String KEY_11 = "a/H3jK8dFq5wEr6tYu2iOp9Z";

	static final String KEY_30 = "a/M1nB4vC7xZ0lK3jH6gF9dS";

	static final String ACCESS_KEY = "AKIDFAKETEST0000000";

	static final String SECRET_KEY = "fakeSecretKeyForTestsOnly0000000000000000";

	// Basenames, anime slugs, titles and song names of the fixture.
	static final List<String> REVEALING = List.of("CowboyBebop-OP1.ogg", "CowboyBebop-ED1.ogg", "DeathNote-OP1.ogg",
			"ObscureOVA-OP1.ogg", "CowboyBebop", "DeathNote", "ObscureOVA", "Cowboy Bebop", "Cowboy%20Bebop",
			"Death Note", "Obscure OVA", "Tank!", "Tank%21", "Real Folk Blues", "the WORLD");

	static final String INDEX = """
			{
			  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000},
			  "11": {"basename": "CowboyBebop-ED1.ogg", "size": 102, "durationMs": 90000},
			  "30": {"basename": "ObscureOVA-OP1.ogg", "size": 104, "durationMs": 85000}
			}
			""";

	static final String SERVED_MAP = """
			{
			  "CowboyBebop-OP1.ogg": {"key": "%s", "size": 101},
			  "CowboyBebop-ED1.ogg": {"key": "%s", "size": 999},
			  "ObscureOVA-OP1.ogg": {"key": "%s", "size": 104}
			}
			""".formatted(KEY_10, KEY_11, KEY_30);

	// Every entry's size differs from the index: no candidate remains.
	static final String SERVED_MAP_NO_MATCH = """
			{
			  "CowboyBebop-OP1.ogg": {"key": "%s", "size": 1},
			  "CowboyBebop-ED1.ogg": {"key": "%s", "size": 2},
			  "ObscureOVA-OP1.ogg": {"key": "%s", "size": 3}
			}
			""".formatted(KEY_10, KEY_11, KEY_30);

	private S3Fixture() {
	}

	// Writes catalog.json, and audio-cache/index.json and audio-cache/s3-keys.json when not null.
	static void write(Path dataDir, String index, String servedMap) {
		try {
			Path cache = dataDir.resolve("audio-cache");
			Files.createDirectories(cache);
			Files.copy(FIXTURE_CATALOG, dataDir.resolve("catalog.json"));
			if (index != null) {
				Files.writeString(cache.resolve("index.json"), index, StandardCharsets.UTF_8);
			}
			if (servedMap != null) {
				Files.writeString(cache.resolve("s3-keys.json"), servedMap, StandardCharsets.UTF_8);
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static AwsCredentialsProvider staticCredentials() {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY));
	}

}
