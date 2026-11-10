package com.songrush.audio;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

// Presigned GetObject URLs for the served map's keys (cycle 074). Signing is local: no request is
// sent. The presigner asks the provider for credentials on every presign, so rotated credentials are
// picked up and a provider failure surfaces per request. With a CDN host (cycle 106) the URL's scheme
// and host are swapped for it; the path and query stay byte for byte.
public class S3AudioLinks implements AutoCloseable {

	static final Region REGION = Region.AP_SOUTHEAST_2;

	static final Duration LIFETIME = Duration.ofSeconds(60);

	private final String bucket;

	private final Map<String, String> keyByBasename;

	private final S3Presigner presigner;

	// Null or blank: the S3 host.
	private final String cdnHost;

	public S3AudioLinks(String bucket, AwsCredentialsProvider credentials, Map<String, String> keyByBasename,
			String cdnHost) {
		this.bucket = bucket;
		this.cdnHost = cdnHost;
		this.keyByBasename = Map.copyOf(keyByBasename);
		this.presigner = S3Presigner.builder()
				.region(REGION)
				.credentialsProvider(credentials)
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(false).build())
				.build();
	}

	// Empty when the basename has no key. A signing failure throws (an SdkException).
	public Optional<String> presign(String basename) {
		String key = keyByBasename.get(basename);
		if (key == null) {
			return Optional.empty();
		}
		GetObjectPresignRequest request = GetObjectPresignRequest.builder()
				.signatureDuration(LIFETIME)
				.getObjectRequest(object -> object.bucket(bucket).key(key))
				.build();
		return Optional.of(CdnUrls.rewrite(presigner.presignGetObject(request).url().toString(), cdnHost));
	}

	@Override
	public void close() {
		presigner.close();
	}

	// Never the bucket's keys.
	@Override
	public String toString() {
		return "S3AudioLinks[keys=" + keyByBasename.size() + "]";
	}

}
