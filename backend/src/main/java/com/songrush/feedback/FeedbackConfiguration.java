package com.songrush.feedback;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// The production IssueSender: GitHub with connect 10 s and request 20 s (cycle 082).
// The FeedbackStore (cycle 083, D-097) lives in songrush.feedback.store-dir, also read from env
// SONGRUSH_FEEDBACK_DIR; blank (the default) disables it, and with it POST /api/feedback.
@Configuration
public class FeedbackConfiguration {

	@Bean
	public GitHubIssueSender gitHubIssueSender(FeedbackProperties properties) {
		return new GitHubIssueSender(properties, Duration.ofSeconds(10), Duration.ofSeconds(20));
	}

	@Bean
	public FeedbackStore feedbackStore(
			@Value("${songrush.feedback.store-dir:${SONGRUSH_FEEDBACK_DIR:}}") String storeDir, Clock clock) {
		String dir = storeDir.strip();
		return new FeedbackStore(dir.isEmpty() ? null : Path.of(dir), clock);
	}

}
