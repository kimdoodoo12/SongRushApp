package com.songrush.audio;

import java.net.URI;
import java.util.regex.Pattern;

// The CDN host swap of a presigned URL (cycle 106, D-118). The server still signs S3 presigned URLs;
// only the scheme and host change to the CloudFront domain, which forwards the query to the private
// bucket, so S3 still checks the signature and expiry. The raw (still-encoded) path and query are kept
// byte for byte: decoding or re-encoding them would break the signature.
public final class CdnUrls {

	// Lowercase DNS labels separated by dots, at least two labels; no scheme, port, path or whitespace.
	private static final Pattern HOST = Pattern
			.compile("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:[.][a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");

	private static final int MAX_HOST_LENGTH = 253;

	private CdnUrls() {
	}

	// Null or blank cdnHost returns the URL unchanged (the switch is off).
	public static String rewrite(String presignedUrl, String cdnHost) {
		if (cdnHost == null || cdnHost.isBlank()) {
			return presignedUrl;
		}
		URI uri = URI.create(presignedUrl);
		String rawPath = uri.getRawPath() == null ? "" : uri.getRawPath();
		String rawQuery = uri.getRawQuery();
		return "https://" + cdnHost + rawPath + (rawQuery == null ? "" : "?" + rawQuery);
	}

	public static boolean validHost(String host) {
		return host != null && host.length() <= MAX_HOST_LENGTH && HOST.matcher(host).matches();
	}

}
