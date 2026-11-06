package com.songrush.audio;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The clock AudioTokens measures expiry with.
@Configuration
public class AudioConfiguration {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}

}
