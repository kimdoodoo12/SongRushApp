package com.songrush;

import org.springframework.boot.context.properties.ConfigurationProperties;

// songrush.audio.bucket (env SONGRUSH_AUDIO_BUCKET): the private S3 bucket the audio is served from
// (cycle 074, design 073). Blank or absent means disk mode: files stream from data-dir/audio-cache.
@ConfigurationProperties(prefix = "songrush.audio")
public record AudioProperties(String bucket) {

	public boolean s3Mode() {
		return bucket != null && !bucket.isBlank();
	}

}
