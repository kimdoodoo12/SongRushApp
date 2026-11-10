package com.songrush.audio;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import com.songrush.AudioProperties;
import com.songrush.SongrushProperties;
import com.songrush.catalog.S3Catalog;
import com.songrush.catalog.S3CatalogLoader;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

// S3 mode (cycle 074, design 073): only when songrush.audio.bucket is not blank. Without it none of
// these beans exist and /audio/{token} streams from disk as before.
@Configuration
@Conditional(S3ModeCondition.class)
public class S3AudioConfiguration {

	// The one provider bean; tests replace it by this name.
	@Bean
	public AwsCredentialsProvider awsCredentialsProvider() {
		return DefaultCredentialsProvider.builder().build();
	}

	@Bean
	public S3CredentialsCheck s3CredentialsCheck(AwsCredentialsProvider awsCredentialsProvider) {
		return new S3CredentialsCheck(awsCredentialsProvider);
	}

	@Bean
	public S3Catalog s3Catalog(SongrushProperties properties) {
		Path cache = properties.dataDir().resolve("audio-cache");
		return S3CatalogLoader.load(properties.dataDir().resolve("catalog.json"), cache.resolve("index.json"),
				cache.resolve("s3-keys.json"));
	}

	@Bean
	public S3AudioLinks s3AudioLinks(AudioProperties audio, CdnProperties cdn,
			AwsCredentialsProvider awsCredentialsProvider, S3Catalog s3Catalog) {
		return new S3AudioLinks(audio.bucket(), awsCredentialsProvider, s3Catalog.keyByBasename(), cdn.cdnHost());
	}

}
