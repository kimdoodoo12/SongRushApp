package com.songrush.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Cycle 054 helpers. writeData makes a minimal valid data folder: the committed test catalog
// (src/test/resources/catalog/catalog.json) and an empty audio-cache/index.json, so the context starts
// with a known catalog and no songs (a lobby create needs none). raw sends an HTTP/1.1 request text
// over a plain java.net.Socket, so neither the Host header nor the path is touched by a client library.
final class WebFixture {

	static final Path FIXTURE_CATALOG = Path.of("src/test/resources/catalog/catalog.json");

	private static final int TIMEOUT_MS = 15_000;

	private WebFixture() {
	}

	static void writeData(Path dir) {
		try {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			Files.copy(FIXTURE_CATALOG, dir.resolve("catalog.json"), StandardCopyOption.REPLACE_EXISTING);
			Files.writeString(cache.resolve("index.json"), "{}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// The status code and the whole response text (headers and body) as ISO-8859-1.
	record RawResponse(int status, String text) {
	}

	// Sends the request text to 127.0.0.1:port and reads until the server closes or goes quiet. An
	// upgrade that succeeds keeps the socket open, so a read timeout ends the read rather than failing.
	static RawResponse raw(int port, String request) {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(TIMEOUT_MS);
			OutputStream out = socket.getOutputStream();
			out.write(request.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			try {
				while (true) {
					int n = in.read(chunk);
					if (n < 0) {
						break;
					}
					buffer.write(chunk, 0, n);
					String sofar = buffer.toString(StandardCharsets.ISO_8859_1);
					// A 101 has no body; stop once its header block is complete.
					if (sofar.startsWith("HTTP/1.1 101") && sofar.contains("\r\n\r\n")) {
						break;
					}
				}
			}
			catch (SocketTimeoutException e) {
				// The server kept the connection open; what was read so far is the response.
			}
			String text = buffer.toString(StandardCharsets.ISO_8859_1);
			return new RawResponse(statusOf(text), text);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static int statusOf(String text) {
		int lineEnd = text.indexOf("\r\n");
		String line = lineEnd < 0 ? text : text.substring(0, lineEnd);
		String[] parts = line.split(" ");
		if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
			throw new IllegalStateException("no HTTP status line in response: '" + line + "'");
		}
		return Integer.parseInt(parts[1]);
	}

	// A GET with Connection: close, so the server ends the response by closing.
	static RawResponse rawGet(int port, String path) {
		return raw(port, "GET " + path + " HTTP/1.1\r\n"
				+ "Host: 127.0.0.1:" + port + "\r\n"
				+ "Connection: close\r\n"
				+ "\r\n");
	}

}
