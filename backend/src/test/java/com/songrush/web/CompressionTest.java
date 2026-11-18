package com.songrush.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
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
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.songrush.audio.AudioTokens;

// Cycle 156: static files are gzipped when the client accepts it (scripts, wasm and fonts of the Flutter
// build), a file under min-response-size is not, and /audio (audio/ogg) is never compressed.
// java.net.http.HttpClient does not decompress, so the raw Content-Encoding header is checked.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionTest {

	private static final int BIG = 8192;

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
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
			// Theme 10 is a draw candidate of the fixture catalog; its index size must match the file.
			Path cache = data.resolve("audio-cache");
			Files.writeString(cache.resolve("index.json"),
					"{\"10\": {\"basename\": \"CowboyBebop-OP1.ogg\", \"size\": " + BIG + ", \"durationMs\": 89000}}",
					StandardCharsets.UTF_8);
			Files.write(cache.resolve("CowboyBebop-OP1.ogg"), repeated("OggS", BIG));
			Path m = Files.createDirectories(web.resolve("m"));
			Files.write(m.resolve("main.dart.js"), repeated("console.log('m');\n", BIG));
			Files.write(m.resolve("canvaskit.wasm"), repeated("\0asm", BIG));
			Files.write(m.resolve("font.ttf"), repeated("glyf", BIG));
			Files.write(m.resolve("small.js"), repeated("x;", 100));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		registry.add("songrush.data-dir", () -> data.toString());
		registry.add("spring.web.resources.static-locations", () -> web.toUri().toString());
		registry.add("spring.web.resources.cache.cachecontrol.no-cache", () -> "true");
	}

	// size bytes of the unit repeated, so the content compresses well.
	private static byte[] repeated(String unit, int size) {
		byte[] pattern = unit.getBytes(StandardCharsets.ISO_8859_1);
		byte[] bytes = new byte[size];
		for (int i = 0; i < size; i++) {
			bytes[i] = pattern[i % pattern.length];
		}
		return bytes;
	}

	@LocalServerPort
	private int port;

	@Autowired
	private AudioTokens tokens;

	private HttpResponse<byte[]> getGzip(String path) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(Duration.ofSeconds(10))
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static byte[] gunzip(byte[] body) throws IOException {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
			return in.readAllBytes();
		}
	}

	private void assertGzipped(String path, String contentType, byte[] original) throws Exception {
		HttpResponse<byte[]> response = getGzip(path);
		assertThat(response.statusCode()).as("status of %s", path).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).as("type of %s", path)
				.hasValueSatisfying(ct -> assertThat(ct).contains(contentType));
		assertThat(response.headers().firstValue("Content-Encoding")).as("encoding of %s", path).hasValue("gzip");
		assertThat(response.body().length).as("compressed size of %s", path).isLessThan(original.length);
		assertThat(gunzip(response.body())).as("content of %s", path).isEqualTo(original);
	}

	@Test
	void scriptIsGzipped() throws Exception {
		assertGzipped("/m/main.dart.js", "javascript", repeated("console.log('m');\n", BIG));
	}

	@Test
	void wasmIsGzipped() throws Exception {
		assertGzipped("/m/canvaskit.wasm", "application/wasm", repeated("\0asm", BIG));
	}

	@Test
	void fontIsGzipped() throws Exception {
		assertGzipped("/m/font.ttf", "font/ttf", repeated("glyf", BIG));
	}

	@Test
	void fileUnderTheMinimumIsNotCompressed() throws Exception {
		HttpResponse<byte[]> response = getGzip("/m/small.js");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
		assertThat(response.body()).isEqualTo(repeated("x;", 100));
	}

	@Test
	void audioIsNeverCompressed() throws Exception {
		HttpResponse<byte[]> response = getGzip("/audio/" + tokens.issue(10, Duration.ofMinutes(1)));
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("audio/ogg");
		assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
		assertThat(response.headers().firstValue("Content-Length")).hasValue(String.valueOf(BIG));
		assertThat(response.body()).isEqualTo(repeated("OggS", BIG));
	}

}
