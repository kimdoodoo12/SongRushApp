package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

// Cycle 144 (C-020): contracts/protocol.md names every message type with a "### server: <type>" or
// "### client: <type>" heading, and contracts/messages holds one example per heading (<type>.json,
// client-<type>.json) that parses as a JSON object whose type is that name, and no example without a
// heading. The headings must also match the code: the client types the handler's switch accepts, and
// the server types Lobby, Room and the handler build. Paths are relative to backend/, Gradle's test
// working directory.
class ProtocolContractTest {

	private static final Path CONTRACTS = Path.of("..", "contracts");

	private static final Path MAIN = Path.of("src", "main", "java", "com", "songrush");

	private static final Pattern HEADING = Pattern.compile("(?m)^### (server|client): (\\S+)\\s*$");

	private static final String CLIENT_PREFIX = "client-";

	@Test
	void everyServerTypeHasACapturedExample() throws IOException {
		Set<String> types = headings("server");
		assertThat(types).isNotEmpty();
		for (String type : types) {
			assertExample(type, type + ".json");
		}
	}

	@Test
	void everyClientTypeHasAnExample() throws IOException {
		Set<String> types = headings("client");
		assertThat(types).isNotEmpty();
		for (String type : types) {
			assertExample(type, CLIENT_PREFIX + type + ".json");
		}
	}

	@Test
	void everyExampleHasAHeading() throws IOException {
		Set<String> expected = new TreeSet<>();
		headings("server").forEach(type -> expected.add(type + ".json"));
		headings("client").forEach(type -> expected.add(CLIENT_PREFIX + type + ".json"));
		Set<String> files = new TreeSet<>();
		try (Stream<Path> list = Files.list(CONTRACTS.resolve("messages"))) {
			list.map(p -> p.getFileName().toString()).forEach(files::add);
		}
		assertThat(files).as("files in contracts/messages").isEqualTo(expected);
	}

	@Test
	void clientTypesMatchTheHandler() throws IOException {
		Set<String> code = matches(read(MAIN.resolve("ws/GameSocketHandler.java")), "case \"([A-Za-z-]+)\" ->");
		assertThat(headings("client")).as("client headings vs GameSocketHandler switch").isEqualTo(code);
	}

	@Test
	void serverTypesMatchTheCode() throws IOException {
		Set<String> code = new TreeSet<>();
		Pattern built = Pattern.compile("message\\(\"([A-Za-z-]+)\"");
		code.addAll(matches(read(MAIN.resolve("game/Lobby.java")), built.pattern()));
		code.addAll(matches(read(MAIN.resolve("game/Room.java")), built.pattern()));
		code.addAll(matches(read(MAIN.resolve("ws/GameSocketHandler.java")), "\\.put\\(\"type\", \"([A-Za-z-]+)\"\\)"));
		assertThat(headings("server")).as("server headings vs the types the code sends").isEqualTo(code);
	}

	private static void assertExample(String type, String fileName) throws IOException {
		Path file = CONTRACTS.resolve("messages").resolve(fileName);
		assertThat(file).as("example for %s", type).isRegularFile();
		JsonNode node = TestClient.MAPPER.readTree(read(file));
		assertThat(node != null && node.isObject()).as("%s is a JSON object", fileName).isTrue();
		assertThat(node.path("type").asString(null)).as("type in %s", fileName).isEqualTo(type);
	}

	private static Set<String> headings(String side) throws IOException {
		Set<String> types = new TreeSet<>();
		Matcher m = HEADING.matcher(read(CONTRACTS.resolve("protocol.md")));
		while (m.find()) {
			if (m.group(1).equals(side)) {
				assertThat(types.add(m.group(2))).as("duplicate heading %s: %s", side, m.group(2)).isTrue();
			}
		}
		return types;
	}

	private static Set<String> matches(String text, String regex) {
		Set<String> out = new TreeSet<>();
		Matcher m = Pattern.compile(regex).matcher(text);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

}
