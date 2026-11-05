package com.songrush.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

// Resolves the AWS credentials once at startup, so S3 mode without credentials fails startup with a
// clear message instead of every /audio request answering 503 (cycle 074, review H3).
public class S3CredentialsCheck {

	private static final Logger log = LoggerFactory.getLogger(S3CredentialsCheck.class);

	public S3CredentialsCheck(AwsCredentialsProvider provider) {
		try {
			provider.resolveCredentials();
		}
		catch (RuntimeException e) {
			throw new IllegalStateException("S3 audio mode: AWS credentials cannot be resolved ("
					+ e.getClass().getSimpleName() + "); configure credentials or leave songrush.audio.bucket blank",
					e);
		}
		log.info("S3 audio mode: AWS credentials resolved");
	}

}
