package com.songrush.cover;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.songrush.AudioProperties;
import com.songrush.audio.CdnProperties;
import com.songrush.audio.CdnUrls;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

// GET /cover/{id} (cycle 093): the reveal's cover. Disk mode streams <id>.jpg (image/jpeg) or
// <id>.png (image/png, cycle 095) from the cover cache, by the file the CoverMap holds; S3 mode
// answers 302 to a 60-second presigned URL of c/<id> in the audio bucket. Like
// AudioController: no Last-Modified, ETag, Content-Disposition or Range support, Cache-Control:
// no-store, and every failure is a 404 raised before any body byte (JSON via JsonErrorController).
// The file and key come from the CoverMap's stored id, never from the request string. Logs carry
// only the id prefix. Covers share nothing with AudioTokens. With songrush.audio.cdn-host set (cycle
// 106) the presigned URL's scheme and host are swapped for the CDN host (CdnUrls).
@RestController
public class CoverController implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CoverController.class);

	private static final int LOGGED_ID_CHARS = 6;

	// The same settings as S3AudioLinks.
	private static final Region REGION = Region.AP_SOUTHEAST_2;

	private static final Duration LIFETIME = Duration.ofSeconds(60);

	private final CoverMap covers;

	private final boolean s3Mode;

	private final String bucket;

	// Null or blank: the S3 host.
	private final String cdnHost;

	// Null in disk mode, or in S3 mode without a credentials provider.
	private final S3Presigner presigner;

	public CoverController(CoverMap covers, AudioProperties audio, CdnProperties cdn,
			ObjectProvider<AwsCredentialsProvider> credentials) {
		this.covers = covers;
		this.s3Mode = audio.s3Mode();
		this.bucket = audio.bucket();
		this.cdnHost = cdn.cdnHost();
		AwsCredentialsProvider provider = s3Mode ? credentials.getIfAvailable() : null;
		if (provider == null) {
			if (s3Mode) {
				log.warn("S3 mode without a credentials provider: covers cannot be served");
			}
			this.presigner = null;
		}
		else {
			this.presigner = S3Presigner.builder()
					.region(REGION)
					.credentialsProvider(provider)
					.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
					.build();
		}
	}

	@GetMapping("/cover/{id}")
	public void cover(@PathVariable String id, HttpServletResponse response) throws IOException {
		String prefix = id.substring(0, Math.min(LOGGED_ID_CHARS, id.length()));
		if (!CoverMap.ID.matcher(id).matches()) {
			log.debug("cover {}...: not a cover id", prefix);
			throw notFound();
		}
		if (s3Mode) {
			redirect(prefix, id, response);
			return;
		}
		Path file = covers.fileFor(id).orElse(null);
		if (file == null) {
			log.debug("cover {}...: unknown", prefix);
			throw notFound();
		}
		InputStream in;
		long size;
		try {
			// Opened before any header is set, so a missing or unreadable file is still a clean 404.
			in = Files.newInputStream(file);
		}
		catch (IOException e) {
			log.warn("cover {}...: file cannot be opened ({})", prefix, e.getClass().getSimpleName());
			throw notFound();
		}
		try (in) {
			try {
				size = Files.size(file);
			}
			catch (IOException e) {
				log.warn("cover {}...: file size cannot be read ({})", prefix, e.getClass().getSimpleName());
				throw notFound();
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType(contentType(file));
			response.setContentLengthLong(size);
			response.setHeader("Cache-Control", "no-store");
			in.transferTo(response.getOutputStream());
		}
	}

	private void redirect(String prefix, String id, HttpServletResponse response) {
		String key = covers.keyFor(id).orElse(null);
		if (key == null) {
			log.debug("cover {}...: unknown", prefix);
			throw notFound();
		}
		if (presigner == null) {
			log.warn("cover {}...: no presigner", prefix);
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		String url;
		try {
			GetObjectPresignRequest request = GetObjectPresignRequest.builder()
					.signatureDuration(LIFETIME)
					.getObjectRequest(object -> object.bucket(bucket).key(key))
					.build();
			url = CdnUrls.rewrite(presigner.presignGetObject(request).url().toString(), cdnHost);
		}
		catch (RuntimeException e) {
			// Only the type: a message could carry the bucket or the key.
			log.warn("cover {}...: presigning failed ({})", prefix, e.getClass().getSimpleName());
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
		}
		response.setStatus(HttpServletResponse.SC_FOUND);
		response.setHeader("Location", url);
		response.setHeader("Cache-Control", "no-store");
		response.setContentLength(0);
	}

	// CoverMap holds only <id>.jpg and <id>.png files.
	private static String contentType(Path file) {
		return file.getFileName().toString().endsWith(".png") ? "image/png" : "image/jpeg";
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND);
	}

	@Override
	public void close() {
		if (presigner != null) {
			presigner.close();
		}
	}

}
