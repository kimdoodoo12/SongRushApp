package com.songrush.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Cycle 054 (B-009) criterion 3: with no static location set, / and /r/{code} stay the JSON 404
// (the /r/{code} forward ends in the resource handler's 404, spec review N1).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoStaticTest {

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	@TempDir
	static Path dataDir;

	@DynamicPropertySource
	static void dataDir(DynamicPropertyRegistry registry) {
		WebFixture.writeData(dataDir);
		registry.add("songrush.data-dir", () -> dataDir.toString());
	}

	@LocalServerPort
	private int port;

	@Test
	void rootAndRoomPathAreJson404() throws Exception {
		for (String path : List.of("/", "/r/ABCDE")) {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
					.timeout(Duration.ofSeconds(10))
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as("status of %s", path).isEqualTo(404);
			assertThat(response.headers().firstValue("Content-Type")).as("content type of %s", path)
					.hasValueSatisfying(ct -> assertThat(ct).startsWith("application/json"));
			assertThat(response.body()).as("body of %s", path).isEqualTo("{\"error\":\"not found\"}");
		}
	}

}
