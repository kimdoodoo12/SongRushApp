package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 082 backend items 2-3 (D-096), changed by cycle 083 backend items 1 and 3 (D-097):
// POST /api/feedback now stores the post as a "new" record and never calls the IssueSender.
//
// declared rewrite (083): test name -> what changed and why
//   validPostCreatesIssue -> validPostStoresNewRecord: asserts the stored record's fields (and that
//       it is on disk) instead of the sent Issue; the post no longer creates an issue (D-097).
//   categoryMapsToKoreanNameAndLabel -> categoryIsStored: asserts the stored category key. The Korean
//       name and label assertions moved unchanged to AdminFeedbackControllerTest#categoryMapsToKoreanNameAndLabel,
//       because the issue is now built at the admin send.
//   atSignsInTitleAreReplaced -> atSignsInTitleAreStoredAsWritten: the record keeps the trimmed title;
//       the '@' -> U+FF20 assertion moved unchanged to AdminFeedbackControllerTest#atSignsInTitleAreReplaced.
//   userTextSitsInsideTheFence -> userTextIsStoredVerbatim: the record keeps the trimmed text; the fence
//       assertions moved unchanged to AdminFeedbackControllerTest#userTextSitsInsideTheFence.
//   backtickRunsAreShortened -> backtickRunsAreStoredAsWritten: the record keeps the text; the shortening
//       assertions moved unchanged to AdminFeedbackControllerTest#backtickRunsAreShortened.
//   titleAndBodyAreTrimmed: same inputs; asserts the stored title/body instead of the Issue.
//   unknownLangAndPageAreStoredAsQuestionMark: same inputs; asserts record lang/page == "?" instead of
//       the Issue body.
//   missingLangAndPageAreAccepted, boundaryLengthsAreAccepted: count stored records instead of sends.
//   invalidInputGives400WithoutSend -> invalidInputGives400WithoutStore; honeypotGives201WithoutSend ->
//       honeypotGives201WithoutStore: nothing stored (and still no send).
//   fourthPostFromOneAddressIsRateLimited, otherAddressIsStillAccepted, globalHourlyCapApplies,
//       rateLimitedPostsDoNotConsumeQuota: "sender.calls()" counts became stored-record counts.
//   sendFailureGives502AndConsumesQuota -> StoreFailure#storeFailureGives502AndConsumesQuota: the
//       failure that gives 502 is now a store write failure (there is no send).
//   Enabled context: now runs with a blank token and a store-dir, because the public form needs only
//       the store (083 backend item 1).
//   Disabled#blankTokenGives503WithoutSend -> BlankStoreDir#blankStoreDirGives503 and
//       DefaultStoreDir#unsetStoreDirGives503: "disabled" now means a blank store-dir, even with a token.
//   New: every Enabled test also asserts the sender is never called; UnwritableStoreDir (503
//       feedback-disabled), Full (503 feedback-full).
//   Unchanged: acceptedAgainAfterTenMinutes, retryAfterFollowsTheRule, retryAfterIsAtLeastOne,
//       perAddressCheckRunsFirst, invalidAndHoneypotPostsDoNotConsumeQuota,
//       honeypotPostsDoNotConsumeGlobalQuota, the invalidBodies list.
//
// Production API this test relies on (package com.songrush.feedback):
//   FeedbackStore, FeedbackRecord (see FeedbackStoreTest); the file format (see FeedbackFiles).
//   Exactly one Spring bean of type FeedbackStore, built from property songrush.feedback.store-dir
//   (env SONGRUSH_FEEDBACK_DIR, default empty) and the context's Clock by type (a @Primary Clock wins).
//   A blank store-dir (after trimming) gives a disabled store. FeedbackPropertiesTest is unchanged, so
//   new FeedbackProperties(token, repo, apiBase) must still compile.
//   The controller takes its IssueSender by type but never calls it on POST /api/feedback.
//   Order: store disabled (503 feedback-disabled) -> invalid (400) -> honeypot (201, nothing stored)
//   -> rate limit (429) -> store.add: FullException gives 503 {"error":"feedback-full"}, an IOException
//   (or any RuntimeException) gives 502 {"error":"feedback-failed"}, success gives 201 {"ok":true}.
//   A post that reaches store.add is counted by the limiter, whatever the outcome (082 rule: counted
//   before the store call).
//   The stored record holds the validated values: category key, trimmed title and text exactly as the
//   user wrote them (no '@' or backtick rewriting: that happens when the issue is built at the admin
//   send), lang/page or "?" when unknown or missing, createdAt = clock.instant().
//   Everything else as cycle 082: exact JSON bodies, the 8 KB / malformed JSON 400 from the controller
//   itself, Retry-After in whole seconds, client address = request.getRemoteAddr().
//
// State: the rate limiter and the store live in cached Spring contexts, so every test first moves the
// clock 2 hours ahead, which empties both windows and makes this test's records the newest ones.
class FeedbackControllerTest {

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final String OK = "{\"ok\":true}";
	private static final String BAD = "{\"error\":\"bad-feedback\"}";
	private static final String LIMITED = "{\"error\":\"rate-limited\"}";
	private static final String FAILED = "{\"error\":\"feedback-failed\"}";
	private static final String DISABLED = "{\"error\":\"feedback-disabled\"}";
	private static final String FULL = "{\"error\":\"feedback-full\"}";

	// Must never be called by POST /api/feedback.
	static final class FakeIssueSender implements IssueSender {

		private int calls;

		@Override
		public synchronized CreatedIssue send(Issue issue) throws IssueSendException {
			calls++;
			return new CreatedIssue(1, "https://github.com/kimdoodoo12/SongRushApp/issues/1");
		}

		synchronized void reset() {
			calls = 0;
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

	static Stream<Arguments> categories() {
		return Stream.of(Arguments.of("bug"), Arguments.of("idea"), Arguments.of("song"), Arguments.of("other"));
	}

	static ResultActions post(MockMvc mvc, String address, String body) throws Exception {
		return mvc.perform(MockMvcRequestBuilders.post("/api/feedback")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body.getBytes(StandardCharsets.UTF_8))
				.with(request -> {
					request.setRemoteAddr(address);
					return request;
				}));
	}

	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class Enabled {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Autowired
		private MockMvc mvc;

		@Autowired
		private MutableClock clock;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		private int before;

		@BeforeEach
		void freshWindows() {
			clock.advance(Duration.ofHours(2));
			sender.reset();
			assertThat(store.enabled()).isTrue();
			before = store.list("all").size();
		}

		private ResultActions send(String address, String body) throws Exception {
			return post(mvc, address, body);
		}

		private void accepted(String address) throws Exception {
			send(address, json(valid())).andExpect(status().is(201)).andExpect(content().string(OK));
		}

		private void limited(String address, String retryAfter) throws Exception {
			send(address, json(valid())).andExpect(status().is(429))
					.andExpect(content().string(LIMITED))
					.andExpect(header().string("Retry-After", retryAfter));
		}

		// The records stored by this test, newest first.
		private List<FeedbackRecord> added() {
			List<FeedbackRecord> all = store.list("all");
			return new ArrayList<>(all.subList(0, all.size() - before));
		}

		private FeedbackRecord onlyRecord() {
			List<FeedbackRecord> added = added();
			assertThat(added).hasSize(1);
			assertThat(sender.calls()).as("POST never calls the IssueSender").isZero();
			return added.get(0);
		}

		// Success: one stored "new" record, 201 {"ok":true}, no send.
		@Test
		void validPostStoresNewRecord() throws Exception {
			Instant now = clock.instant();
			Map<String, Object> map = valid();
			map.put("title", "Audio stops");
			map.put("body", "The song cut off at 30 s.");
			map.put("lang", "ja");
			map.put("page", "lobby");
			send("10.0.0.1", json(map)).andExpect(status().is(201)).andExpect(content().string(OK));

			FeedbackRecord record = onlyRecord();
			assertThat(record.id()).matches("[A-Za-z0-9_-]{12}");
			assertThat(record.createdAt()).isEqualTo(now);
			assertThat(record.category()).isEqualTo("bug");
			assertThat(record.title()).isEqualTo("Audio stops");
			assertThat(record.body()).isEqualTo("The song cut off at 30 s.");
			assertThat(record.lang()).isEqualTo("ja");
			assertThat(record.page()).isEqualTo("lobby");
			assertThat(record.status()).isEqualTo("new");
			assertThat(record.issueNumber()).isNull();
			assertThat(record.issueUrl()).isNull();
			assertThat(new FeedbackStore(DIR, clock).get(record.id())).as("persisted to disk").contains(record);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.FeedbackControllerTest#categories")
		void categoryIsStored(String category) throws Exception {
			send("10.0.0.2", json(with("category", category))).andExpect(status().is(201));
			assertThat(onlyRecord().category()).isEqualTo(category);
		}

		@Test
		void atSignsInTitleAreStoredAsWritten() throws Exception {
			send("10.0.0.3", json(with("title", "ping @owner and @@team"))).andExpect(status().is(201));
			assertThat(onlyRecord().title()).isEqualTo("ping @owner and @@team");
		}

		@Test
		void userTextIsStoredVerbatim() throws Exception {
			String text = "line1\n\tline2 <b>bold</b> @owner #1";
			send("10.0.0.4", json(with("body", text))).andExpect(status().is(201));
			assertThat(onlyRecord().body()).isEqualTo(text);
		}

		@Test
		void backtickRunsAreStoredAsWritten() throws Exception {
			String text = "a ```` b ``````` c ``` d ` e";
			send("10.0.0.5", json(with("body", text))).andExpect(status().is(201));
			assertThat(onlyRecord().body()).isEqualTo(text);
		}

		@Test
		void titleAndBodyAreTrimmed() throws Exception {
			Map<String, Object> map = valid();
			map.put("title", "  spaced title  ");
			map.put("body", " \n spaced body \n ");
			send("10.0.0.6", json(map)).andExpect(status().is(201));
			FeedbackRecord record = onlyRecord();
			assertThat(record.title()).isEqualTo("spaced title");
			assertThat(record.body()).isEqualTo("spaced body");
		}

		@Test
		void unknownLangAndPageAreStoredAsQuestionMark() throws Exception {
			Map<String, Object> map = valid();
			map.put("lang", "zzlang");
			map.put("page", "zzpage");
			send("10.0.0.7", json(map)).andExpect(status().is(201));
			FeedbackRecord record = onlyRecord();
			assertThat(record.lang()).isEqualTo("?");
			assertThat(record.page()).isEqualTo("?");
		}

		@Test
		void missingLangAndPageAreAccepted() throws Exception {
			Map<String, Object> map = valid();
			map.remove("lang");
			map.remove("page");
			map.remove("website");
			send("10.0.0.8", json(map)).andExpect(status().is(201)).andExpect(content().string(OK));
			FeedbackRecord record = onlyRecord();
			assertThat(record.lang()).isEqualTo("?");
			assertThat(record.page()).isEqualTo("?");
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
			assertThat(added()).hasSize(4);
			assertThat(sender.calls()).isZero();
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.FeedbackControllerTest#invalidBodies")
		void invalidInputGives400WithoutStore(String name, String body) throws Exception {
			send("10.0.2.1", body).andExpect(status().is(400)).andExpect(content().string(BAD));
			assertThat(added()).isEmpty();
			assertThat(sender.calls()).isZero();
		}

		@Test
		void honeypotGives201WithoutStore() throws Exception {
			send("10.0.2.2", json(with("website", "http://spam.example"))).andExpect(status().is(201))
					.andExpect(content().string(OK));
			assertThat(added()).isEmpty();
			assertThat(sender.calls()).isZero();
		}

		// The 4th counted post from one address within 10 minutes is refused.
		@Test
		void fourthPostFromOneAddressIsRateLimited() throws Exception {
			accepted("10.0.3.1");
			accepted("10.0.3.1");
			accepted("10.0.3.1");
			limited("10.0.3.1", "600");
			assertThat(added()).hasSize(3);
		}

		@Test
		void otherAddressIsStillAccepted() throws Exception {
			accepted("10.0.3.2");
			accepted("10.0.3.2");
			accepted("10.0.3.2");
			limited("10.0.3.2", "600");
			accepted("10.0.3.3");
			assertThat(added()).hasSize(4);
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
			assertThat(added()).hasSize(30);
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
			assertThat(added()).hasSize(6);
			assertThat(sender.calls()).isZero();
		}
	}

	// A store write failure gives 502 and still consumes quota (the 082 "send failure" rule, now for the store).
	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class StoreFailure {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Autowired
		private MockMvc mvc;

		@Autowired
		private MutableClock clock;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		@Test
		void storeFailureGives502AndConsumesQuota() throws Exception {
			clock.advance(Duration.ofHours(2));
			sender.reset();
			assertThat(store.enabled()).isTrue();
			FeedbackFiles.breakDir(DIR);
			try {
				for (int i = 0; i < 3; i++) {
					post(mvc, "10.0.4.1", json(valid())).andExpect(status().is(502))
							.andExpect(content().string(FAILED));
				}
			}
			finally {
				FeedbackFiles.restoreDir(DIR);
			}
			post(mvc, "10.0.4.1", json(valid())).andExpect(status().is(429))
					.andExpect(content().string(LIMITED))
					.andExpect(header().string("Retry-After", "600"));
			// The store works again once the folder is back.
			post(mvc, "10.0.4.2", json(with("title", "after repair"))).andExpect(status().is(201))
					.andExpect(content().string(OK));
			assertThat(new FeedbackStore(DIR, clock).list("all")).extracting(FeedbackRecord::title)
					.contains("after repair");
			assertThat(sender.calls()).isZero();
		}
	}

	// 1000 "new" records already stored: a valid post gives 503 feedback-full and stores nothing.
	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class Full {

		static final Path DIR = seeded();

		private static Path seeded() {
			Path dir = FeedbackFiles.newStoreDir();
			FeedbackFiles.writeFile(dir,
					FeedbackFiles.seed(1000, Instant.parse("2026-10-01T00:00:00Z"), i -> "new"));
			return dir;
		}

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Autowired
		private MockMvc mvc;

		@Autowired
		private MutableClock clock;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		@Test
		void fullStoreGives503() throws Exception {
			clock.advance(Duration.ofHours(2));
			sender.reset();
			assertThat(store.list("new")).hasSize(1000);
			post(mvc, "10.0.7.1", json(valid())).andExpect(status().is(503)).andExpect(content().string(FULL));
			assertThat(store.list("all")).hasSize(1000);
			assertThat(new FeedbackStore(DIR, clock).list("all")).hasSize(1000);
			assertThat(sender.calls()).isZero();
		}
	}

	private static void expectDisabled(MockMvc mvc, FakeIssueSender sender) throws Exception {
		sender.reset();
		post(mvc, "10.9.0.1", json(valid())).andExpect(status().is(503)).andExpect(content().string(DISABLED));
		assertThat(sender.calls()).isZero();
	}

	// A token alone does not enable the public form: a blank store-dir disables it.
	@Nested
	@SpringBootTest(properties = { "songrush.feedback.token=test-token", "songrush.feedback.store-dir=   " })
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class BlankStoreDir {

		@Autowired
		private MockMvc mvc;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		@Test
		void blankStoreDirGives503() throws Exception {
			assertThat(store.enabled()).isFalse();
			expectDisabled(mvc, sender);
		}
	}

	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=test-token")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class DefaultStoreDir {

		@Autowired
		private MockMvc mvc;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		@Test
		void unsetStoreDirGives503() throws Exception {
			assertThat(store.enabled()).isFalse();
			expectDisabled(mvc, sender);
		}
	}

	// A store-dir that cannot be created (a regular file stands at its path) disables storage.
	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class UnwritableStoreDir {

		static final Path FILE = FeedbackFiles.newRegularFile();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> FILE.toString());
		}

		@Autowired
		private MockMvc mvc;

		@Autowired
		private FakeIssueSender sender;

		@Autowired
		private FeedbackStore store;

		@Test
		void unwritableStoreDirGives503() throws Exception {
			assertThat(store.enabled()).isFalse();
			expectDisabled(mvc, sender);
		}
	}
}
