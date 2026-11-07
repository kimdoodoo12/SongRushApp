package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 082 backend items 2-3 (D-096): POST /api/feedback.
//
// Production API this test relies on (package com.songrush.feedback):
//   public record Issue(String title, String body, List<String> labels)
//   public interface IssueSender { void send(Issue issue) throws IssueSendException; }
//   public class IssueSendException extends Exception { public IssueSendException(String message); ... }
//   FeedbackProperties (see FeedbackPropertiesTest).
//   A @RestController handling POST /api/feedback that gets its IssueSender and its java.time.Clock
//   from the Spring context by type. The app already has a Clock bean (AudioConfiguration); the
//   feedback code must read time only from an injected Clock, never Instant.now()/System time.
//   This test adds @Primary beans of type IssueSender (a fake) and Clock (MutableClock), so
//   production code must inject IssueSender and Clock by type (a @Primary bean must win).
//   The controller (not the sender) validates, rate-limits and builds the Issue:
//     title  = "[문의/<ko category>] " + trimmed title with every '@' replaced by U+FF20;
//     labels = ["문의", <category label>];
//     body   = Markdown containing: the category, the language (or "?"), the page (or "?"),
//              clock.instant() formatted as ISO-8601 UTC (Instant.toString form, e.g.
//              2026-11-07T02:00:00Z), the trimmed user text in a fence written exactly as
//              "````text\n" + text + "\n````" (runs of 4+ backticks shortened to 3), and the footer
//              "_사이트 문의하기로 접수됨_". The fence must be the only place with 4 backticks in a row.
//   Every response body is exact JSON: {"ok":true}, {"error":"feedback-disabled"},
//   {"error":"bad-feedback"}, {"error":"rate-limited"} (with header Retry-After: <int seconds>),
//   {"error":"feedback-failed"}. Malformed JSON and an over-8 KB body must yield the 400 JSON from
//   the controller itself (MockMvc does no error dispatch to JsonErrorController).
//   The client address is request.getRemoteAddr().
//
// State: the rate limiter lives in a cached Spring context, so every test first moves the clock
// 2 hours ahead, which empties both windows whatever earlier tests did.
class FeedbackControllerTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final String OK = "{\"ok\":true}";
	private static final String BAD = "{\"error\":\"bad-feedback\"}";
	private static final String LIMITED = "{\"error\":\"rate-limited\"}";
	private static final String FAILED = "{\"error\":\"feedback-failed\"}";
	private static final String DISABLED = "{\"error\":\"feedback-disabled\"}";
	private static final String FOOTER = "_사이트 문의하기로 접수됨_";
	private static final String FULLWIDTH_AT = String.valueOf((char) 0xFF20);

	enum Mode { OK, FAIL, CRASH }

	static final class FakeIssueSender implements IssueSender {

		private final List<Issue> sent = new ArrayList<>();
		private volatile Mode mode = Mode.OK;
		private int calls;

		@Override
		public synchronized void send(Issue issue) throws IssueSendException {
			calls++;
			if (mode == Mode.FAIL) {
				throw new IssueSendException("fake GitHub failure");
			}
			if (mode == Mode.CRASH) {
				throw new IllegalStateException("fake crash");
			}
			sent.add(issue);
		}

		synchronized void reset() {
			sent.clear();
			calls = 0;
			mode = Mode.OK;
		}

		synchronized List<Issue> sent() {
			return List.copyOf(sent);
		}

		synchronized int calls() {
			return calls;
		}
	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		MutableClock feedbackTestClock() {
			return new MutableClock();
		}

		@Bean
		@Primary
		FakeIssueSender fakeIssueSender() {
			return new FakeIssueSender();
		}
	}

	static Map<String, Object> valid() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("category", "bug");
		map.put("title", "Hello");
		map.put("body", "World");
		map.put("lang", "ko");
		map.put("page", "start");
		map.put("website", "");
		return map;
	}

	static Map<String, Object> with(String key, Object value) {
		Map<String, Object> map = valid();
		map.put(key, value);
		return map;
	}

	static Map<String, Object> without(String key) {
		Map<String, Object> map = valid();
		map.remove(key);
		return map;
	}

	static String json(Map<String, Object> map) {
		return MAPPER.writeValueAsString(map);
	}

	static String repeat(String unit, int times) {
		return unit.repeat(times);
	}

	static String emoji() {
		return new String(Character.toChars(0x1F600));
	}

	static int count(String haystack, String needle) {
		int n = 0;
		int from = 0;
		while ((from = haystack.indexOf(needle, from)) >= 0) {
			n++;
			from += needle.length();
		}
		return n;
	}

	// A body whose decoded text is 2000 code units (valid) but whose JSON is 12000+ bytes,
	// because every character is written as a six-byte backslash-u JSON escape.
	static String oversizeJson() {
		String escaped = repeat("\\" + "u00e9", 2000);
		return "{\"category\":\"bug\",\"title\":\"Hello\",\"body\":\"" + escaped
				+ "\",\"lang\":\"ko\",\"page\":\"start\",\"website\":\"\"}";
	}

	static Stream<Arguments> invalidBodies() {
		String bel = String.valueOf((char) 7);
		String esc = String.valueOf((char) 27);
		return Stream.of(
				Arguments.of("malformed json", "{\"category\":"),
				Arguments.of("empty body", ""),
				Arguments.of("json null", "null"),
				Arguments.of("json array", "[]"),
				Arguments.of("missing category", json(without("category"))),
				Arguments.of("unknown category", json(with("category", "feature"))),
				Arguments.of("null category", json(with("category", null))),
				Arguments.of("missing title", json(without("title"))),
				Arguments.of("empty title", json(with("title", ""))),
				Arguments.of("blank title", json(with("title", "   "))),
				Arguments.of("title 81", json(with("title", repeat("a", 81)))),
				Arguments.of("title 41 emoji = 82 code units", json(with("title", repeat(emoji(), 41)))),
				Arguments.of("missing body", json(without("body"))),
				Arguments.of("empty body text", json(with("body", ""))),
				Arguments.of("blank body text", json(with("body", " \n\t "))),
				Arguments.of("body 2001", json(with("body", repeat("b", 2001)))),
				Arguments.of("title with tab", json(with("title", "a\tb"))),
				Arguments.of("title with newline", json(with("title", "a\nb"))),
				Arguments.of("title with BEL", json(with("title", "a" + bel + "b"))),
				Arguments.of("body with BEL", json(with("body", "a" + bel + "b"))),
				Arguments.of("body with ESC", json(with("body", "a" + esc + "[31mb"))),
				Arguments.of("over 8 KB", oversizeJson()));
	}

	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=test-token")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class Enabled {

		@Autowired
		private MockMvc mvc;

		@Autowired
		private MutableClock clock;

		@Autowired
		private FakeIssueSender sender;

		@BeforeEach
		void freshWindows() {
			clock.advance(Duration.ofHours(2));
			sender.reset();
		}

		private ResultActions send(String address, String body) throws Exception {
			return mvc.perform(post("/api/feedback")
					.contentType(MediaType.APPLICATION_JSON)
					.content(body.getBytes(StandardCharsets.UTF_8))
					.with(request -> {
						request.setRemoteAddr(address);
						return request;
					}));
		}

		private void accepted(String address) throws Exception {
			send(address, json(valid())).andExpect(status().is(201)).andExpect(content().string(OK));
		}

		private void limited(String address, String retryAfter) throws Exception {
			send(address, json(valid())).andExpect(status().is(429))
					.andExpect(content().string(LIMITED))
					.andExpect(header().string("Retry-After", retryAfter));
		}

		private Issue onlyIssue() {
			assertThat(sender.sent()).hasSize(1);
			return sender.sent().get(0);
		}

		// Success: one send, 201 {"ok":true}, right title, labels and body content.
		@Test
		void validPostCreatesIssue() throws Exception {
			String now = clock.instant().toString();
			Map<String, Object> map = valid();
			map.put("title", "Audio stops");
			map.put("body", "The song cut off at 30 s.");
			map.put("lang", "ja");
			map.put("page", "lobby");
			send("10.0.0.1", json(map)).andExpect(status().is(201)).andExpect(content().string(OK));

			Issue issue = onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/버그] Audio stops");
			assertThat(issue.labels()).containsExactly("문의", "bug");
			assertThat(issue.body())
					.contains("ja")
					.contains("lobby")
					.contains(now)
					.contains("````text\nThe song cut off at 30 s.\n````")
					.contains(FOOTER);
			assertThat(issue.body().contains("bug") || issue.body().contains("버그")).as("category in body").isTrue();
			assertThat(sender.calls()).isEqualTo(1);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.FeedbackControllerTest#categories")
		void categoryMapsToKoreanNameAndLabel(String category, String korean, String label) throws Exception {
			send("10.0.0.2", json(with("category", category))).andExpect(status().is(201));
			Issue issue = onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/" + korean + "] Hello");
			assertThat(issue.labels()).containsExactly("문의", label);
		}

		@Test
		void atSignsInTitleAreReplaced() throws Exception {
			send("10.0.0.3", json(with("title", "ping @owner and @@team"))).andExpect(status().is(201));
			Issue issue = onlyIssue();
			assertThat(issue.title())
					.isEqualTo("[문의/버그] ping " + FULLWIDTH_AT + "owner and " + FULLWIDTH_AT + FULLWIDTH_AT + "team")
					.doesNotContain("@");
		}

		@Test
		void userTextSitsInsideTheFence() throws Exception {
			String text = "line1\n\tline2 <b>bold</b> @owner #1";
			send("10.0.0.4", json(with("body", text))).andExpect(status().is(201));
			String body = onlyIssue().body();
			assertThat(body).contains("````text\n" + text + "\n````");
			assertThat(count(body, "<b>bold</b>")).as("user text appears once").isEqualTo(1);
			assertThat(count(body, "````")).as("only the fence has 4 backticks").isEqualTo(2);
		}

		@Test
		void backtickRunsAreShortened() throws Exception {
			String text = "a ```` b ``````` c ``` d ` e";
			send("10.0.0.5", json(with("body", text))).andExpect(status().is(201));
			String body = onlyIssue().body();
			assertThat(body).contains("````text\na ``` b ``` c ``` d ` e\n````");
			assertThat(count(body, "````")).isEqualTo(2);
			assertThat(body).doesNotContain("`````");
		}

		@Test
		void titleAndBodyAreTrimmed() throws Exception {
			Map<String, Object> map = valid();
			map.put("title", "  spaced title  ");
			map.put("body", " \n spaced body \n ");
			send("10.0.0.6", json(map)).andExpect(status().is(201));
			Issue issue = onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/버그] spaced title");
			assertThat(issue.body()).contains("````text\nspaced body\n````");
		}

		@Test
		void unknownLangAndPageAreStoredAsQuestionMark() throws Exception {
			Map<String, Object> map = valid();
			map.put("lang", "zzlang");
			map.put("page", "zzpage");
			send("10.0.0.7", json(map)).andExpect(status().is(201));
			String body = onlyIssue().body();
			assertThat(body).doesNotContain("zzlang").doesNotContain("zzpage").contains("?");
		}

		@Test
		void missingLangAndPageAreAccepted() throws Exception {
			Map<String, Object> map = valid();
			map.remove("lang");
			map.remove("page");
			map.remove("website");
			send("10.0.0.8", json(map)).andExpect(status().is(201)).andExpect(content().string(OK));
			assertThat(sender.sent()).hasSize(1);
		}

		@Test
		void boundaryLengthsAreAccepted() throws Exception {
			// 80 and 2000 code units, after trimming; 40 emoji are 80 UTF-16 code units.
			send("10.0.1.1", json(with("title", " " + repeat("a", 80) + " "))).andExpect(status().is(201));
			send("10.0.1.2", json(with("title", repeat(emoji(), 40)))).andExpect(status().is(201));
			// 2000 Korean characters are 6000 UTF-8 bytes: still under the 8 KB limit.
			Map<String, Object> big = valid();
			big.put("title", repeat("가", 80));
			big.put("body", repeat("가", 2000));
			send("10.0.1.3", json(big)).andExpect(status().is(201));
			// The body may contain newline and tab.
			send("10.0.1.4", json(with("body", "a\tb\nc\n\td"))).andExpect(status().is(201));
			assertThat(sender.sent()).hasSize(4);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.FeedbackControllerTest#invalidBodies")
		void invalidInputGives400WithoutSend(String name, String body) throws Exception {
			send("10.0.2.1", body).andExpect(status().is(400)).andExpect(content().string(BAD));
			assertThat(sender.calls()).isZero();
		}

		@Test
		void honeypotGives201WithoutSend() throws Exception {
			send("10.0.2.2", json(with("website", "http://spam.example"))).andExpect(status().is(201))
					.andExpect(content().string(OK));
			assertThat(sender.calls()).isZero();
		}

		// The 4th counted post from one address within 10 minutes is refused.
		@Test
		void fourthPostFromOneAddressIsRateLimited() throws Exception {
			accepted("10.0.3.1");
			accepted("10.0.3.1");
			accepted("10.0.3.1");
			limited("10.0.3.1", "600");
			assertThat(sender.calls()).isEqualTo(3);
		}

		@Test
		void otherAddressIsStillAccepted() throws Exception {
			accepted("10.0.3.2");
			accepted("10.0.3.2");
			accepted("10.0.3.2");
			limited("10.0.3.2", "600");
			accepted("10.0.3.3");
			assertThat(sender.calls()).isEqualTo(4);
		}

		@Test
		void acceptedAgainAfterTenMinutes() throws Exception {
			accepted("10.0.3.4");
			accepted("10.0.3.4");
			accepted("10.0.3.4");
			clock.advance(Duration.ofMinutes(10));
			accepted("10.0.3.4");
		}

		// Retry-After = seconds until the oldest counted timestamp in the violated window expires,
		// rounded up, minimum 1.
		@Test
		void retryAfterFollowsTheRule() throws Exception {
			accepted("10.0.3.5"); // t0
			clock.advance(Duration.ofSeconds(100));
			accepted("10.0.3.5"); // t0+100
			clock.advance(Duration.ofSeconds(100));
			accepted("10.0.3.5"); // t0+200
			clock.advance(Duration.ofMillis(50_500));
			limited("10.0.3.5", "350"); // t0+250.5: 349.5 s left, rounded up
			clock.advance(Duration.ofMillis(349_500));
			accepted("10.0.3.5"); // t0+600: t0 has expired
			limited("10.0.3.5", "100"); // oldest is now t0+100
		}

		@Test
		void retryAfterIsAtLeastOne() throws Exception {
			accepted("10.0.3.6");
			accepted("10.0.3.6");
			accepted("10.0.3.6");
			clock.advance(Duration.ofMillis(599_999));
			limited("10.0.3.6", "1");
			clock.advance(Duration.ofMillis(1));
			accepted("10.0.3.6");
		}

		@Test
		void globalHourlyCapApplies() throws Exception {
			for (int i = 1; i <= 30; i++) {
				accepted("10.1.0." + i);
			}
			clock.advance(Duration.ofSeconds(60));
			limited("10.1.1.1", "3540");
			assertThat(sender.calls()).isEqualTo(30);
			clock.advance(Duration.ofSeconds(3540));
			accepted("10.1.1.1");
		}

		@Test
		void perAddressCheckRunsFirst() throws Exception {
			accepted("10.2.0.1");
			accepted("10.2.0.1");
			accepted("10.2.0.1");
			for (int i = 1; i <= 27; i++) {
				accepted("10.2.1." + i);
			}
			// Both limits are violated; the per-address window decides Retry-After.
			limited("10.2.0.1", "600");
			limited("10.2.2.1", "3600");
		}

		@Test
		void sendFailureGives502AndConsumesQuota() throws Exception {
			sender.mode = Mode.FAIL;
			send("10.0.4.1", json(valid())).andExpect(status().is(502)).andExpect(content().string(FAILED));
			sender.mode = Mode.CRASH;
			send("10.0.4.1", json(valid())).andExpect(status().is(502)).andExpect(content().string(FAILED));
			send("10.0.4.1", json(valid())).andExpect(status().is(502)).andExpect(content().string(FAILED));
			sender.mode = Mode.OK;
			limited("10.0.4.1", "600");
			assertThat(sender.calls()).isEqualTo(3);
		}

		@Test
		void invalidAndHoneypotPostsDoNotConsumeQuota() throws Exception {
			for (int i = 0; i < 5; i++) {
				send("10.0.5.1", json(with("category", "nope"))).andExpect(status().is(400));
				send("10.0.5.1", "{oops").andExpect(status().is(400));
				send("10.0.5.1", json(with("website", "bot"))).andExpect(status().is(201));
			}
			accepted("10.0.5.1");
			accepted("10.0.5.1");
			accepted("10.0.5.1");
			limited("10.0.5.1", "600");
		}

		@Test
		void honeypotPostsDoNotConsumeGlobalQuota() throws Exception {
			for (int i = 1; i <= 40; i++) {
				send("10.3.0." + i, json(with("website", "bot"))).andExpect(status().is(201));
			}
			for (int i = 1; i <= 30; i++) {
				accepted("10.3.1." + i);
			}
		}

		@Test
		void rateLimitedPostsDoNotConsumeQuota() throws Exception {
			accepted("10.0.6.1"); // t0
			accepted("10.0.6.1");
			accepted("10.0.6.1");
			clock.advance(Duration.ofSeconds(300));
			limited("10.0.6.1", "300");
			limited("10.0.6.1", "300");
			limited("10.0.6.1", "300");
			clock.advance(Duration.ofSeconds(300)); // t0+600: if the 429s had counted, this would fail
			accepted("10.0.6.1");
			accepted("10.0.6.1");
			accepted("10.0.6.1");
			assertThat(sender.calls()).isEqualTo(6);
		}
	}

	static Stream<Arguments> categories() {
		return Stream.of(
				Arguments.of("bug", "버그", "bug"),
				Arguments.of("idea", "건의", "enhancement"),
				Arguments.of("song", "곡 요청", "song-request"),
				Arguments.of("other", "기타", "question"));
	}

	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class Disabled {

		@Autowired
		private MockMvc mvc;

		@Autowired
		private FakeIssueSender sender;

		@Test
		void blankTokenGives503WithoutSend() throws Exception {
			sender.reset();
			mvc.perform(post("/api/feedback")
					.contentType(MediaType.APPLICATION_JSON)
					.content(json(valid()).getBytes(StandardCharsets.UTF_8))
					.with(request -> {
						request.setRemoteAddr("10.9.0.1");
						return request;
					}))
					.andExpect(status().is(503))
					.andExpect(content().string(DISABLED));
			assertThat(sender.calls()).isZero();
		}
	}
}
