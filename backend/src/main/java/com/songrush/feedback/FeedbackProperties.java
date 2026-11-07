package com.songrush.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// songrush.feedback.* (cycle 082, D-096). The token (env SONGRUSH_FEEDBACK_TOKEN) is trimmed when
// bound. Since cycle 083 (D-097) it is needed only by the admin send action: a blank token makes that
// answer 503 github-disabled, while public posts depend on the store-dir alone (FeedbackConfiguration).
// toString never shows the token.
@ConfigurationProperties(prefix = "songrush.feedback")
public record FeedbackProperties(String token, @DefaultValue("kimdoodoo12/SongRushApp") String repo,
		@DefaultValue("https://api.github.com") String apiBase) {

	public FeedbackProperties {
		token = token == null ? "" : token.strip();
	}

	public boolean hasToken() {
		return !token.isEmpty();
	}

	@Override
	public String toString() {
		return "FeedbackProperties[token=" + (hasToken() ? "<set>" : "<empty>") + ", repo=" + repo + ", apiBase="
				+ apiBase + "]";
	}

}
