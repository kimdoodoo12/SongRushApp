package com.songrush.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.songrush.SongrushProperties;
import com.songrush.catalog.Candidate;
import com.songrush.catalog.Catalog;

// GET /audio/{token}: streams a draw candidate's cached file. Nothing in the response names the song:
// no Last-Modified, ETag, Content-Disposition or Range support (cycle 033). Every failure is a 404
// raised before any body byte, so JsonErrorController writes {"error":"not found"}.
// Logs carry only the token prefix.
// In S3 mode (cycle 074) a candidate's token answers 302 to a 60-second presigned URL of its random
// object key instead, with Cache-Control: no-store and no body; a signing failure is a 503.
@RestController
public class AudioController {

	private static final Logger log = LoggerFactory.getLogger(AudioController.class);

	private static final int LOGGED_TOKEN_CHARS = 6;

	private final AudioTokens tokens;

	private final Catalog catalog;

	private final Path cacheDir;

	// Null in disk mode.
	private final S3AudioLinks links;

	public AudioController(AudioTokens tokens, Catalog catalog, SongrushProperties properties,
			ObjectProvider<S3AudioLinks> links) {
		this.tokens = tokens;
		this.catalog = catalog;
		this.cacheDir = properties.dataDir().resolve("audio-cache");
		this.links = links.getIfAvailable();
	}

	@GetMapping("/audio/{token}")
	public void audio(@PathVariable String token, HttpServletResponse response) throws IOException {
		String prefix = token.substring(0, Math.min(LOGGED_TOKEN_CHARS, token.length()));
		Integer themeId = tokens.resolve(token).orElse(null);
		if (themeId == null) {
			log.debug("audio token {}...: unknown or expired", prefix);
			throw notFound();
		}
		Candidate candidate = catalog.candidate(themeId).orElse(null);
		if (candidate == null) {
			log.warn("audio token {}...: not a draw candidate", prefix);
			throw notFound();
		}
		if (links != null) {
			redirect(prefix, candidate, response);
			return;
		}
		Path file = cacheDir.resolve(candidate.audioBasename());
		InputStream in;
		long size;
		try {
			// Opened before any header is set, so a missing or unreadable file is still a clean 404.
			in = Files.newInputStream(file);
		}
		catch (IOException e) {
			log.warn("audio token {}...: cached file cannot be opened ({})", prefix, e.getClass().getSimpleName());
			throw notFound();
		}
		try (in) {
			try {
				size = Files.size(file);
			}
			catch (IOException e) {
				log.warn("audio token {}...: cached file size cannot be read ({})", prefix,
						e.getClass().getSimpleName());
				throw notFound();
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("audio/ogg");
			response.setContentLengthLong(size);
			response.setHeader("Cache-Control", "no-store");
			// Streams in fixed-size chunks: memory per request stays constant.
			in.transferTo(response.getOutputStream());
		}
	}

	private void redirect(String prefix, Candidate candidate, HttpServletResponse response) {
		String url;
		try {
			url = links.presign(candidate.audioBasename()).orElse(null);
		}
		catch (RuntimeException e) {
			// Only the type: a message could carry the bucket or the key.
			log.warn("audio token {}...: presigning failed ({})", prefix, e.getClass().getSimpleName());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		if (url == null) {
			log.warn("audio token {}...: candidate has no object key", prefix);
			throw notFound();
		}
		response.setStatus(HttpServletResponse.SC_FOUND);
		response.setHeader("Location", url);
		response.setHeader("Cache-Control", "no-store");
		response.setContentLength(0);
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

}
