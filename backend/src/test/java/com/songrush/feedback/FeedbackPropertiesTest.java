package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Cycle 082 backend item 1 (D-096).
//
// Production API this test relies on (package com.songrush.feedback):
//   @ConfigurationProperties(prefix = "songrush.feedback")
//   public record FeedbackProperties(String token, String repo, String apiBase)
//     - picked up by the existing @ConfigurationPropertiesScan, like SongrushProperties;
//     - defaults: token "" (env SONGRUSH_FEEDBACK_TOKEN binds to it), repo "kimdoodoo12/SongRushApp",
//       apiBase (property songrush.feedback.api-base) "https://api.github.com";
//     - the canonical constructor trims the token (null becomes ""); a blank token disables the feature;
//     - toString() is overridden and never contains the token.
class FeedbackPropertiesTest {

	@Test
	void toStringHidesToken() {
		FeedbackProperties properties = new FeedbackProperties("t0k-secret", "kimdoodoo12/SongRushApp",
				"https://api.github.com");
		assertThat(properties.toString()).doesNotContain("t0k-secret");
		assertThat(properties.token()).isEqualTo("t0k-secret");
	}

	@Test
	void tokenIsTrimmed() {
		assertThat(new FeedbackProperties("t0k-secret\r\n", "o/r", "http://x").token()).isEqualTo("t0k-secret");
		assertThat(new FeedbackProperties("  t0k-secret  ", "o/r", "http://x").token()).isEqualTo("t0k-secret");
		assertThat(new FeedbackProperties("t0k-secret\n", "o/r", "http://x").token()).isEqualTo("t0k-secret");
	}

	@Test
	void blankOrNullTokenBecomesEmpty() {
		assertThat(new FeedbackProperties(" \r\n ", "o/r", "http://x").token()).isEmpty();
		assertThat(new FeedbackProperties(null, "o/r", "http://x").token()).isEmpty();
	}

	@Nested
	@SpringBootTest
	class Defaults {

		@Autowired
		private FeedbackProperties properties;

		@Test
		void repoAndApiBaseHaveDefaults() {
			assertThat(properties.repo()).isEqualTo("kimdoodoo12/SongRushApp");
			assertThat(properties.apiBase()).isEqualTo("https://api.github.com");
		}
	}

	@Nested
	@SpringBootTest(properties = { "songrush.feedback.token=abc-token", "songrush.feedback.repo=o/r",
			"songrush.feedback.api-base=http://127.0.0.1:9" })
	class Override {

		@Autowired
		private FeedbackProperties properties;

		@Test
		void valuesBind() {
			assertThat(properties.token()).isEqualTo("abc-token");
			assertThat(properties.repo()).isEqualTo("o/r");
			assertThat(properties.apiBase()).isEqualTo("http://127.0.0.1:9");
			assertThat(properties.toString()).doesNotContain("abc-token");
		}
	}
}
