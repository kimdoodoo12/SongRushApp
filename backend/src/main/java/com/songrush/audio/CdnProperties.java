package com.songrush.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;

// songrush.audio.cdn-host (env SONGRUSH_AUDIO_CDNHOST, cycle 106, D-118): the CloudFront domain the S3
// presigned audio and cover URLs are rewritten to. Blank or absent means the S3 host as before. A
// separate record from AudioProperties so S3ModeCondition's binding stays as it is. An invalid
// non-blank value fails binding, so the application does not start.
@ConfigurationProperties(prefix = "songrush.audio")
public record CdnProperties(String cdnHost) {

	public CdnProperties {
		if (cdnHost != null && !cdnHost.isBlank() && !CdnUrls.validHost(cdnHost)) {
			throw new IllegalArgumentException("songrush.audio.cdn-host must be a lowercase DNS host name such as"
					+ " d1234abcd.cloudfront.net, without scheme, port or path");
		}
	}

}
