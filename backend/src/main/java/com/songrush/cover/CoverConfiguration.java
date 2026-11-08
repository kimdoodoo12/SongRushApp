package com.songrush.cover;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.songrush.AudioProperties;
import com.songrush.SongrushProperties;

// The one CoverMap bean (cycle 093): the served map in S3 mode, the disk index otherwise. Never fails
// startup; a broken cover map is an empty one.
@Configuration
public class CoverConfiguration {

	@Bean
	public CoverMap coverMap(SongrushProperties properties, AudioProperties audio) {
		Path cache = properties.dataDir().resolve("cover-cache");
		if (audio.s3Mode()) {
			return CoverMap.loadS3(cache.resolve("s3-covers.json"));
		}
		return CoverMap.loadDisk(cache);
	}

}
