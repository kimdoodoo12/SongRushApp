package com.songrush.feedback;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The production IssueSender: GitHub with connect 10 s and request 20 s (cycle 082).
@Configuration
public class FeedbackConfiguration {

	@Bean
	public GitHubIssueSender gitHubIssueSender(FeedbackProperties properties) {
		return new GitHubIssueSender(properties, Duration.ofSeconds(10), Duration.ofSeconds(20));
	}

}
