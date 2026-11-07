package com.songrush.feedback;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// POST {api-base}/repos/{repo}/issues with the JDK HttpClient (cycle 082). One call, no retry.
// Logs carry only the GitHub status or the failure type: never the token or the issue text,
// and exception messages follow the same rule (no cause is attached, as a cause's message is not ours).
public class GitHubIssueSender implements IssueSender {

	private static final Logger log = LoggerFactory.getLogger(GitHubIssueSender.class);

	private static final String USER_AGENT = "SongRushBot/1.0 (+https://animeopguessr.com)";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private final FeedbackProperties properties;

	private final Duration requestTimeout;

	private final HttpClient client;

	public GitHubIssueSender(FeedbackProperties properties, Duration connectTimeout, Duration requestTimeout) {
		this.properties = properties;
		this.requestTimeout = requestTimeout;
		// HTTP/1.1: no h2c upgrade attempt against a plain-http api-base (the local fake in tests).
		this.client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.build();
	}

	@Override
	public void send(Issue issue) throws IssueSendException {
		int status;
		try {
			HttpResponse<Void> response = client.send(request(issue), HttpResponse.BodyHandlers.discarding());
			status = response.statusCode();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("feedback: GitHub call interrupted");
			throw new IssueSendException("GitHub call interrupted");
		}
		catch (IOException | RuntimeException e) {
			log.warn("feedback: GitHub call failed ({})", e.getClass().getSimpleName());
			throw new IssueSendException("GitHub call failed (" + e.getClass().getSimpleName() + ")");
		}
		if (status >= 200 && status < 300) {
			log.info("feedback: GitHub created the issue (status {})", status);
			return;
		}
		if (status == 401 || status == 403) {
			log.error("feedback: GitHub rejected the token (status {}) - renew SONGRUSH_FEEDBACK_TOKEN", status);
		}
		else {
			log.warn("feedback: GitHub refused the issue (status {})", status);
		}
		throw new IssueSendException("GitHub answered status " + status);
	}

	private HttpRequest request(Issue issue) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("title", issue.title());
		json.put("body", issue.body());
		json.put("labels", issue.labels());
		byte[] body = MAPPER.writeValueAsBytes(json);
		String base = properties.apiBase();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return HttpRequest.newBuilder(URI.create(base + "/repos/" + properties.repo() + "/issues"))
				.timeout(requestTimeout)
				.setHeader("Authorization", "Bearer " + properties.token())
				.setHeader("Accept", "application/vnd.github+json")
				.setHeader("X-GitHub-Api-Version", "2022-11-28")
				.setHeader("User-Agent", USER_AGENT)
				.setHeader("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
	}

}
