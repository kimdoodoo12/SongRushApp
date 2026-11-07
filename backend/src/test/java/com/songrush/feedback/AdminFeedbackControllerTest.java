package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 083 backend items 1 and 4 (D-097): the admin API under /admin/api.
//
// Production API this test relies on (package com.songrush.feedback):
//   FeedbackStore, FeedbackRecord, CreatedIssue (see FeedbackStoreTest); AdminProperties (see
//   AdminPropertiesTest), bound from songrush.admin.key; FeedbackProperties.token (songrush.feedback.token).
//   public interface IssueSender { CreatedIssue send(Issue issue) throws IssueSendException; }
//   The admin controller takes FeedbackStore, IssueSender and Clock by type (this test's @Primary fakes win).
//
//   Key (authoritative lock, checked on the matched handler before anything else): every /admin/api/**
//   request needs header X-Admin-Key equal to the trimmed songrush.admin.key (constant-time compare).
//   Blank key, missing header or wrong value -> 404, before the 403 check and before any lookup.
//   Mutating requests (POST) also need X-Requested-With: songrush-admin and a Content-Type whose media
//   type is application/json (parameters such as charset allowed); otherwise 403 {"error":"forbidden"}
//   (checked in code, not with "consumes", which would give 415). GET needs only the key.
//   Every response from /admin/api/** (including the 404 and 403 above) has a Cache-Control header
//   containing "no-store".
//
//   GET  /admin/api/feedback?status=new|sent|hidden|all (default new) -> 200 {"items":[record...]},
//        newest first (same order as FeedbackStore.list).
//   record JSON = the FeedbackRecord components by name; createdAt/updatedAt as Instant.toString()
//        strings (e.g. "2026-11-07T02:00:00Z"); issueNumber an int and issueUrl a string once sent.
//   POST /admin/api/feedback/{id}/send, body {} or {"title": string|null}
//        - unknown id -> 404 {"error":"not-found"}; record "sent" -> 409 {"error":"already-sent"};
//          send in flight for this id -> 409 {"error":"in-flight"};
//        - title present (not null): trimmed and checked with the 082 title rules (1-80 UTF-16 code units,
//          no control characters), else 400 {"error":"bad-feedback"}; a non-string title, malformed JSON
//          or a non-object body is also 400 bad-feedback;
//        - blank songrush.feedback.token -> 503 {"error":"github-disabled"} (no send);
//        - the Issue is built from the stored record with the 082 format: title "[문의/<ko category>] " +
//          (edited or stored) title with '@' -> U+FF20; labels ["문의", <category label>]; body with the
//          category, lang, page, the record's createdAt (Instant.toString, not the send time), the text in
//          "````text\n" + text (4+ backtick runs shortened to 3) + "\n````", and the footer
//          "_사이트 문의하기로 접수됨_". Without an edited title the body has exactly one fence. With an
//          edited title the body also has the original stored title in its own fence, written exactly
//          "````text\n" + original (4+ backtick runs shortened to 3) + "\n````" (two fences in total);
//        - sender success -> record becomes "sent" with issueNumber/issueUrl from CreatedIssue, updatedAt
//          = clock.instant(), written to the store; 200 with the record JSON (no "persisted", or true);
//        - sender throws IssueSendException or RuntimeException -> 502 {"error":"feedback-failed"},
//          the record stays "new" and the in-flight mark is cleared;
//        - the store write fails after the sender succeeded -> the record stays "sent" in memory, an ERROR
//          log line contains the issue number, and the answer is 200 with the record plus "persisted":false;
//          the next successful write of the store persists it;
//        - the store lock is not held during the sender call: public POST /api/feedback and the list keep
//          answering while a send is blocked.
//   POST /admin/api/feedback/{id}/hide   new -> hidden;  POST .../unhide  hidden -> new
//        200 with the record (updatedAt = clock.instant()); sent -> 409 already-sent; unknown -> 404;
//        while a send is in flight for the id -> 409 in-flight.
//
// State: the store lives in a cached Spring context; every test moves the clock an hour ahead first, so
// its own records are the newest ones.
class AdminFeedbackControllerTest {

	static final String KEY = "adm1n-key-for-tests-0123456789";

	static final String XRW = "songrush-admin";

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final String FOOTER = "_사이트 문의하기로 접수됨_";

	private static final String FULLWIDTH_AT = String.valueOf((char) 0xFF20);

	enum Mode { OK, FAIL, CRASH, BLOCK }

	static String issueUrl(int number) {
		return "https://github.com/kimdoodoo12/SongRushApp/issues/" + number;
	}

	static final class FakeIssueSender implements IssueSender {

		private final List<Issue> issues = new ArrayList<>();
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger next = new AtomicInteger(100);
		private volatile Mode mode = Mode.OK;
		private volatile CountDownLatch entered = new CountDownLatch(1);
		private volatile CountDownLatch release = new CountDownLatch(1);

		@Override
		public CreatedIssue send(Issue issue) throws IssueSendException {
			calls.incrementAndGet();
			synchronized (this) {
				issues.add(issue);
			}
			Mode current = mode;
			if (current == Mode.BLOCK) {
				entered.countDown();
				try {
					if (!release.await(30, TimeUnit.SECONDS)) {
						throw new IssueSendException("fake GitHub never released");
					}
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IssueSendException("fake GitHub interrupted");
				}
			}
			if (current == Mode.FAIL) {
				throw new IssueSendException("fake GitHub failure");
			}
			if (current == Mode.CRASH) {
				throw new IllegalStateException("fake crash");
			}
			int number = next.getAndIncrement();
			return new CreatedIssue(number, issueUrl(number));
		}

		synchronized void reset() {
			issues.clear();
			calls.set(0);
			mode = Mode.OK;
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		synchronized List<Issue> issues() {
			return List.copyOf(issues);
		}

		Issue onlyIssue() {
			assertThat(issues()).hasSize(1);
			return issues().get(0);
		}

		int calls() {
			return calls.get();
		}
	}

	@TestConfiguration
	static class Fakes {

		@Bean
		@Primary
		MutableClock adminTestClock() {
			return new MutableClock();
		}

		@Bean
		@Primary
		FakeIssueSender adminFakeIssueSender() {
			return new FakeIssueSender();
		}
	}

	// ---- request builders -------------------------------------------------------------------------

	static MockHttpServletRequestBuilder list(String status) {
		MockHttpServletRequestBuilder builder = get("/admin/api/feedback").header("X-Admin-Key", KEY)
				.header("X-Requested-With", XRW);
		return status == null ? builder : builder.param("status", status);
	}

	// A bare POST to /admin/api/feedback/{id}/{action} with a JSON body and no headers.
	static MockHttpServletRequestBuilder bare(String id, String action, String body) {
		return post("/admin/api/feedback/{id}/{action}", id, action)
				.content(body.getBytes(StandardCharsets.UTF_8));
	}

	static MockHttpServletRequestBuilder action(String id, String action, String body) {
		return bare(id, action, body).header("X-Admin-Key", KEY)
				.header("X-Requested-With", XRW)
				.contentType(MediaType.APPLICATION_JSON);
	}

	static MockHttpServletRequestBuilder action(String id, String action) {
		return action(id, action, "{}");
	}

	static String titleBody(Object title) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("title", title);
		return MAPPER.writeValueAsString(map);
	}

	static JsonNode json(MvcResult result) throws Exception {
		return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	static void expectError(MvcResult result, int status, String error) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		assertThat(json(result)).isEqualTo(MAPPER.readTree("{\"error\":\"" + error + "\"}"));
	}

	static void expectNotFoundWithoutHint(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("forbidden")
				.doesNotContain("items");
	}

	static String repeat(String unit, int times) {
		return unit.repeat(times);
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

	static List<String> ids(JsonNode items) {
		List<String> ids = new ArrayList<>();
		items.forEach(item -> ids.add(item.path("id").asString(null)));
		return ids;
	}

	static Stream<Arguments> categories() {
		return Stream.of(
				Arguments.of("bug", "버그", "bug"),
				Arguments.of("idea", "건의", "enhancement"),
				Arguments.of("song", "곡 요청", "song-request"),
				Arguments.of("other", "기타", "question"));
	}

	static Stream<Arguments> invalidTitles() {
		String bel = String.valueOf((char) 7);
		return Stream.of(
				Arguments.of("empty title", titleBody("")),
				Arguments.of("blank title", titleBody("   ")),
				Arguments.of("title 81", titleBody(repeat("a", 81))),
				Arguments.of("title 41 emoji = 82 code units", titleBody(repeat(new String(Character.toChars(0x1F600)), 41))),
				Arguments.of("title with tab", titleBody("a\tb")),
				Arguments.of("title with newline", titleBody("a\nb")),
				Arguments.of("title with BEL", titleBody("a" + bel + "b")),
				Arguments.of("number title", "{\"title\":5}"),
				Arguments.of("object title", "{\"title\":{}}"),
				Arguments.of("malformed json", "{\"title\":"),
				Arguments.of("json array", "[]"));
	}

	// ---- a running store, key, token ---------------------------------------------------------------

	// Shared by the nested contexts below.
	abstract static class Base {

		@Autowired
		MockMvc mvc;

		@Autowired
		MutableClock clock;

		@Autowired
		FakeIssueSender sender;

		@Autowired
		FeedbackStore store;

		// Performs an /admin/api request and checks the no-store header that every such response carries.
		MvcResult admin(MockHttpServletRequestBuilder request) throws Exception {
			MvcResult result = mvc.perform(request).andReturn();
			assertThat(result.getResponse().getHeader("Cache-Control"))
					.as("Cache-Control of %s %s (status %d)", result.getRequest().getMethod(),
							result.getRequest().getRequestURI(), result.getResponse().getStatus())
					.isNotNull()
					.contains("no-store");
			return result;
		}

		FeedbackRecord add(String category, String title, String body) throws Exception {
			return store.add(category, title, body, "ja", "lobby");
		}

		FeedbackRecord add() throws Exception {
			return add("bug", "Audio stops", "The song cut off at 30 s.");
		}

		String statusOf(FeedbackRecord record) {
			return store.get(record.id()).orElseThrow().status();
		}
	}

	@Nested
	@SpringBootTest(properties = { "songrush.feedback.token=test-token",
			"songrush.admin.key=  " + KEY + "  " })
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class Enabled extends Base {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@BeforeEach
		void fresh() {
			clock.advance(Duration.ofHours(1));
			sender.reset();
			assertThat(store.enabled()).isTrue();
		}

		// ---- the key ----

		private List<MockHttpServletRequestBuilder> everyRoute(String id, String key) {
			List<MockHttpServletRequestBuilder> routes = new ArrayList<>();
			routes.add(get("/admin/api/feedback").header("X-Requested-With", XRW));
			routes.add(get("/admin/api/feedback").param("status", "all").header("X-Requested-With", XRW));
			for (String action : List.of("send", "hide", "unhide")) {
				routes.add(bare(id, action, "{}").header("X-Requested-With", XRW)
						.contentType(MediaType.APPLICATION_JSON));
			}
			if (key != null) {
				routes.forEach(route -> route.header("X-Admin-Key", key));
			}
			return routes;
		}

		@Test
		void missingKeyGives404() throws Exception {
			FeedbackRecord record = add();
			for (MockHttpServletRequestBuilder route : everyRoute(record.id(), null)) {
				expectNotFoundWithoutHint(admin(route));
			}
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(sender.calls()).isZero();
		}

		@ParameterizedTest
		@ValueSource(strings = { "wrong", KEY + "x", "dm1n-key-for-tests-0123456789", "ADM1N-KEY-FOR-TESTS-0123456789",
				"", "  " })
		void wrongKeyGives404(String key) throws Exception {
			FeedbackRecord record = add();
			for (MockHttpServletRequestBuilder route : everyRoute(record.id(), key)) {
				expectNotFoundWithoutHint(admin(route));
			}
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(sender.calls()).isZero();
		}

		// The key check runs before the 403 check and before the id lookup.
		@Test
		void keyCheckComesFirst() throws Exception {
			FeedbackRecord record = add();
			expectNotFoundWithoutHint(admin(bare(record.id(), "hide", "{}").contentType(MediaType.TEXT_PLAIN)));
			expectNotFoundWithoutHint(admin(bare("nosuchid0000", "send", "{}").header("X-Admin-Key", "wrong")));
			assertThat(statusOf(record)).isEqualTo("new");
		}

		@Test
		void listNeedsOnlyTheKey() throws Exception {
			MvcResult result = admin(get("/admin/api/feedback").header("X-Admin-Key", KEY));
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(json(result).path("items").isArray()).isTrue();
		}

		// ---- 403 for mutating requests ----

		@ParameterizedTest
		@ValueSource(strings = { "send", "hide", "unhide" })
		void mutatingRequestWithoutRequiredHeadersGives403(String action) throws Exception {
			FeedbackRecord record = add();
			if (action.equals("unhide")) {
				store.hide(record.id());
			}
			String before = statusOf(record);
			List<MockHttpServletRequestBuilder> bad = List.of(
					// no X-Requested-With
					bare(record.id(), action, "{}").header("X-Admin-Key", KEY).contentType(MediaType.APPLICATION_JSON),
					// wrong X-Requested-With
					bare(record.id(), action, "{}").header("X-Admin-Key", KEY).header("X-Requested-With", "XMLHttpRequest")
							.contentType(MediaType.APPLICATION_JSON),
					// no Content-Type
					bare(record.id(), action, "{}").header("X-Admin-Key", KEY).header("X-Requested-With", XRW),
					// simple-request content types a cross-site form can send
					bare(record.id(), action, "{}").header("X-Admin-Key", KEY).header("X-Requested-With", XRW)
							.contentType(MediaType.TEXT_PLAIN),
					bare(record.id(), action, "title=x").header("X-Admin-Key", KEY).header("X-Requested-With", XRW)
							.contentType(MediaType.APPLICATION_FORM_URLENCODED),
					bare(record.id(), action, "{}").header("X-Admin-Key", KEY).header("X-Requested-With", XRW)
							.contentType(MediaType.MULTIPART_FORM_DATA));
			for (MockHttpServletRequestBuilder request : bad) {
				expectError(admin(request), 403, "forbidden");
			}
			assertThat(statusOf(record)).isEqualTo(before);
			assertThat(sender.calls()).isZero();
		}

		@Test
		void jsonWithCharsetParameterIsAccepted() throws Exception {
			FeedbackRecord record = add();
			MvcResult result = admin(bare(record.id(), "hide", "{}").header("X-Admin-Key", KEY)
					.header("X-Requested-With", XRW)
					.contentType(MediaType.parseMediaType("application/json;charset=UTF-8")));
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(statusOf(record)).isEqualTo("hidden");
		}

		// ---- the list ----

		@Test
		void listFiltersByStatusNewestFirst() throws Exception {
			FeedbackRecord a = add("bug", "a", "body a");
			clock.advance(Duration.ofSeconds(1));
			FeedbackRecord b = add("idea", "b", "body b");
			clock.advance(Duration.ofSeconds(1));
			FeedbackRecord c = add("song", "c", "body c");
			clock.advance(Duration.ofSeconds(1));
			FeedbackRecord d = add("other", "d <b>x</b>", "body d\n\tline2");
			store.hide(b.id());
			assertThat(admin(action(c.id(), "send")).getResponse().getStatus()).isEqualTo(200);

			List<String> byDefault = ids(json(admin(list(null))).path("items"));
			assertThat(byDefault.subList(0, 2)).containsExactly(d.id(), a.id());
			assertThat(byDefault).doesNotContain(b.id(), c.id());

			List<String> fresh = ids(json(admin(list("new"))).path("items"));
			assertThat(fresh).isEqualTo(byDefault);

			List<String> hidden = ids(json(admin(list("hidden"))).path("items"));
			assertThat(hidden.get(0)).isEqualTo(b.id());
			assertThat(hidden).doesNotContain(a.id(), c.id(), d.id());

			List<String> sent = ids(json(admin(list("sent"))).path("items"));
			assertThat(sent.get(0)).isEqualTo(c.id());
			assertThat(sent).doesNotContain(a.id(), b.id(), d.id());

			List<String> all = ids(json(admin(list("all"))).path("items"));
			assertThat(all.subList(0, 4)).containsExactly(d.id(), c.id(), b.id(), a.id());

			JsonNode item = json(admin(list("new"))).path("items").get(0);
			assertThat(item.path("id").asString(null)).isEqualTo(d.id());
			assertThat(item.path("createdAt").asString(null)).isEqualTo(d.createdAt().toString());
			assertThat(item.path("updatedAt").asString(null)).isEqualTo(d.updatedAt().toString());
			assertThat(item.path("category").asString(null)).isEqualTo("other");
			assertThat(item.path("title").asString(null)).isEqualTo("d <b>x</b>");
			assertThat(item.path("body").asString(null)).isEqualTo("body d\n\tline2");
			assertThat(item.path("lang").asString(null)).isEqualTo("ja");
			assertThat(item.path("page").asString(null)).isEqualTo("lobby");
			assertThat(item.path("status").asString(null)).isEqualTo("new");

			JsonNode sentItem = json(admin(list("sent"))).path("items").get(0);
			assertThat(sentItem.path("status").asString(null)).isEqualTo("sent");
			int number = sentItem.path("issueNumber").asInt(-1);
			assertThat(number).isGreaterThanOrEqualTo(100);
			assertThat(sentItem.path("issueUrl").asString(null)).isEqualTo(issueUrl(number));
		}

		// ---- send: the issue (082 format, built from the stored record) ----

		// Moved from 082 FeedbackControllerTest#validPostCreatesIssue.
		@Test
		void sendBuildsThe082Issue() throws Exception {
			FeedbackRecord record = add();
			String createdAt = record.createdAt().toString();
			clock.advance(Duration.ofMinutes(5));
			String sendTime = clock.instant().toString();
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);

			Issue issue = sender.onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/버그] Audio stops");
			assertThat(issue.labels()).containsExactly("문의", "bug");
			assertThat(issue.body())
					.contains("ja")
					.contains("lobby")
					.contains(createdAt)
					.doesNotContain(sendTime)
					.contains("````text\nThe song cut off at 30 s.\n````")
					.contains(FOOTER);
			assertThat(issue.body().contains("bug") || issue.body().contains("버그")).as("category in body").isTrue();
			assertThat(count(issue.body(), "````")).as("one fence without an edited title").isEqualTo(2);
			assertThat(sender.calls()).isEqualTo(1);
		}

		// Moved from 082 FeedbackControllerTest#categoryMapsToKoreanNameAndLabel.
		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.AdminFeedbackControllerTest#categories")
		void categoryMapsToKoreanNameAndLabel(String category, String korean, String label) throws Exception {
			FeedbackRecord record = add(category, "Hello", "World");
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			Issue issue = sender.onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/" + korean + "] Hello");
			assertThat(issue.labels()).containsExactly("문의", label);
		}

		// Moved from 082 FeedbackControllerTest#atSignsInTitleAreReplaced.
		@Test
		void atSignsInTitleAreReplaced() throws Exception {
			FeedbackRecord record = add("bug", "ping @owner and @@team", "World");
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			assertThat(sender.onlyIssue().title())
					.isEqualTo("[문의/버그] ping " + FULLWIDTH_AT + "owner and " + FULLWIDTH_AT + FULLWIDTH_AT + "team")
					.doesNotContain("@");
		}

		// Moved from 082 FeedbackControllerTest#userTextSitsInsideTheFence.
		@Test
		void userTextSitsInsideTheFence() throws Exception {
			String text = "line1\n\tline2 <b>bold</b> @owner #1";
			FeedbackRecord record = add("bug", "Hello", text);
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			String body = sender.onlyIssue().body();
			assertThat(body).contains("````text\n" + text + "\n````");
			assertThat(count(body, "<b>bold</b>")).as("user text appears once").isEqualTo(1);
			assertThat(count(body, "````")).as("only the fence has 4 backticks").isEqualTo(2);
		}

		// Moved from 082 FeedbackControllerTest#backtickRunsAreShortened.
		@Test
		void backtickRunsAreShortened() throws Exception {
			FeedbackRecord record = add("bug", "Hello", "a ```` b ``````` c ``` d ` e");
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			String body = sender.onlyIssue().body();
			assertThat(body).contains("````text\na ``` b ``` c ``` d ` e\n````");
			assertThat(count(body, "````")).isEqualTo(2);
			assertThat(body).doesNotContain("`````");
		}

		@Test
		void editedTitleIsUsedAndOriginalIsKeptInItsOwnFence() throws Exception {
			FeedbackRecord record = add("idea", "original title", "the text");
			MvcResult result = admin(action(record.id(), "send", titleBody("  Better title @dev  ")));
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			Issue issue = sender.onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/건의] Better title " + FULLWIDTH_AT + "dev");
			assertThat(issue.labels()).containsExactly("문의", "enhancement");
			assertThat(issue.body())
					.contains("````text\noriginal title\n````")
					.contains("````text\nthe text\n````")
					.contains(record.createdAt().toString())
					.contains(FOOTER);
			assertThat(count(issue.body(), "````")).as("two fences").isEqualTo(4);
		}

		@Test
		void originalTitleFenceShortensBacktickRuns() throws Exception {
			FeedbackRecord record = add("bug", "a ```` b ````` c", "the text");
			assertThat(admin(action(record.id(), "send", titleBody("clean title"))).getResponse().getStatus())
					.isEqualTo(200);
			String body = sender.onlyIssue().body();
			assertThat(body).contains("````text\na ``` b ``` c\n````");
			assertThat(count(body, "````")).isEqualTo(4);
			assertThat(body).doesNotContain("`````");
		}

		@ParameterizedTest
		@ValueSource(strings = { "{}", "{\"title\":null}" })
		void noEditedTitleUsesTheStoredTitle(String body) throws Exception {
			FeedbackRecord record = add("bug", "stored title", "the text");
			assertThat(admin(action(record.id(), "send", body)).getResponse().getStatus()).isEqualTo(200);
			Issue issue = sender.onlyIssue();
			assertThat(issue.title()).isEqualTo("[문의/버그] stored title");
			assertThat(count(issue.body(), "````")).isEqualTo(2);
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("com.songrush.feedback.AdminFeedbackControllerTest#invalidTitles")
		void invalidEditedTitleGives400(String name, String body) throws Exception {
			FeedbackRecord record = add();
			expectError(admin(action(record.id(), "send", body)), 400, "bad-feedback");
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(sender.calls()).isZero();
		}

		@Test
		void boundaryEditedTitleIsAccepted() throws Exception {
			FeedbackRecord record = add();
			String title = repeat("가", 80);
			assertThat(admin(action(record.id(), "send", titleBody(" " + title + " "))).getResponse().getStatus())
					.isEqualTo(200);
			assertThat(sender.onlyIssue().title()).isEqualTo("[문의/버그] " + title);
		}

		// ---- send: outcomes ----

		@Test
		void successStoresNumberAndUrl() throws Exception {
			FeedbackRecord record = add();
			clock.advance(Duration.ofMinutes(3));
			Instant sentAt = clock.instant();
			MvcResult result = admin(action(record.id(), "send"));
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			JsonNode body = json(result);
			assertThat(body.path("id").asString(null)).isEqualTo(record.id());
			assertThat(body.path("status").asString(null)).isEqualTo("sent");
			assertThat(body.path("issueNumber").asInt(-1)).isEqualTo(100);
			assertThat(body.path("issueUrl").asString(null)).isEqualTo(issueUrl(100));
			assertThat(body.path("title").asString(null)).isEqualTo("Audio stops");
			assertThat(body.path("persisted").asBoolean(true)).as("no persisted:false on success").isTrue();

			FeedbackRecord stored = store.get(record.id()).orElseThrow();
			assertThat(stored.status()).isEqualTo("sent");
			assertThat(stored.issueNumber()).isEqualTo(100);
			assertThat(stored.issueUrl()).isEqualTo(issueUrl(100));
			assertThat(stored.updatedAt()).isEqualTo(sentAt);
			assertThat(stored.createdAt()).isEqualTo(record.createdAt());
			assertThat(new FeedbackStore(DIR, clock).get(record.id())).as("persisted to disk").contains(stored);
		}

		@Test
		void secondSendGives409() throws Exception {
			FeedbackRecord record = add();
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			expectError(admin(action(record.id(), "send")), 409, "already-sent");
			expectError(admin(action(record.id(), "send", titleBody("other title"))), 409, "already-sent");
			assertThat(sender.calls()).isEqualTo(1);
			assertThat(store.get(record.id()).orElseThrow().issueNumber()).isEqualTo(100);
		}

		// One send blocks inside the fake GitHub. Meanwhile: a second send, hide and unhide on the same
		// record get 409 in-flight; the list and a public post still answer (the lock is not held).
		@Test
		void concurrentSendGives409InFlightWithOneSenderCall() throws Exception {
			FeedbackRecord record = add();
			FeedbackRecord other = add("idea", "other", "other text");
			sender.mode = Mode.BLOCK;
			ExecutorService pool = Executors.newCachedThreadPool();
			try {
				Future<MvcResult> first = pool.submit(() -> admin(action(record.id(), "send")));
				assertThat(sender.entered.await(10, TimeUnit.SECONDS)).as("first send reached GitHub").isTrue();

				expectError(within(pool, () -> admin(action(record.id(), "send"))), 409, "in-flight");
				expectError(within(pool, () -> admin(action(record.id(), "send", titleBody("x")))), 409, "in-flight");
				expectError(within(pool, () -> admin(action(record.id(), "hide"))), 409, "in-flight");
				expectError(within(pool, () -> admin(action(record.id(), "unhide"))), 409, "in-flight");

				MvcResult listed = within(pool, () -> admin(list("new")));
				assertThat(listed.getResponse().getStatus()).isEqualTo(200);
				MvcResult hidOther = within(pool, () -> admin(action(other.id(), "hide")));
				assertThat(hidOther.getResponse().getStatus()).as("another record is not blocked").isEqualTo(200);
				MvcResult publicPost = within(pool, () -> mvc.perform(post("/api/feedback")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"category\":\"bug\",\"title\":\"during send\",\"body\":\"x\",\"website\":\"\"}"
								.getBytes(StandardCharsets.UTF_8))
						.with(request -> {
							request.setRemoteAddr("10.8.0.1");
							return request;
						})).andReturn());
				assertThat(publicPost.getResponse().getStatus()).as("public post during a send").isEqualTo(201);

				sender.release.countDown();
				MvcResult done = first.get(10, TimeUnit.SECONDS);
				assertThat(done.getResponse().getStatus()).isEqualTo(200);
				assertThat(json(done).path("status").asString(null)).isEqualTo("sent");
			}
			finally {
				sender.release.countDown();
				pool.shutdownNow();
			}
			assertThat(sender.calls()).as("exactly one GitHub call").isEqualTo(1);
			assertThat(statusOf(record)).isEqualTo("sent");
			expectError(admin(action(record.id(), "hide")), 409, "already-sent");
		}

		private MvcResult within(ExecutorService pool, Callable<MvcResult> call) throws Exception {
			return pool.submit(call).get(10, TimeUnit.SECONDS);
		}

		@ParameterizedTest
		@ValueSource(strings = { "FAIL", "CRASH" })
		void senderFailureGives502AndRecordStaysNew(String mode) throws Exception {
			FeedbackRecord record = add();
			sender.mode = Mode.valueOf(mode);
			expectError(admin(action(record.id(), "send")), 502, "feedback-failed");
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(new FeedbackStore(DIR, clock).get(record.id()).orElseThrow().status()).isEqualTo("new");

			// The in-flight mark is cleared: the record can be sent (or hidden) again.
			sender.mode = Mode.OK;
			MvcResult retry = admin(action(record.id(), "send"));
			assertThat(retry.getResponse().getStatus()).isEqualTo(200);
			assertThat(statusOf(record)).isEqualTo("sent");
			assertThat(sender.calls()).isEqualTo(2);
		}

		@Test
		void unknownIdGives404() throws Exception {
			expectError(admin(action("nosuchid0000", "send")), 404, "not-found");
			for (String action : List.of("hide", "unhide")) {
				assertThat(admin(action("nosuchid0000", action)).getResponse().getStatus()).isEqualTo(404);
			}
			assertThat(sender.calls()).isZero();
		}

		// ---- hide / unhide ----

		@Test
		void hideAndUnhide() throws Exception {
			FeedbackRecord record = add();
			clock.advance(Duration.ofMinutes(1));
			MvcResult hidden = admin(action(record.id(), "hide"));
			assertThat(hidden.getResponse().getStatus()).isEqualTo(200);
			assertThat(json(hidden).path("id").asString(null)).isEqualTo(record.id());
			assertThat(json(hidden).path("status").asString(null)).isEqualTo("hidden");
			assertThat(json(hidden).path("updatedAt").asString(null)).isEqualTo(clock.instant().toString());
			assertThat(ids(json(admin(list("hidden"))).path("items"))).contains(record.id());
			assertThat(ids(json(admin(list("new"))).path("items"))).doesNotContain(record.id());
			assertThat(new FeedbackStore(DIR, clock).get(record.id()).orElseThrow().status()).isEqualTo("hidden");

			clock.advance(Duration.ofMinutes(1));
			MvcResult restored = admin(action(record.id(), "unhide"));
			assertThat(restored.getResponse().getStatus()).isEqualTo(200);
			assertThat(json(restored).path("status").asString(null)).isEqualTo("new");
			assertThat(json(restored).path("updatedAt").asString(null)).isEqualTo(clock.instant().toString());
			assertThat(ids(json(admin(list("new"))).path("items"))).contains(record.id());
			assertThat(new FeedbackStore(DIR, clock).get(record.id()).orElseThrow().status()).isEqualTo("new");
			assertThat(sender.calls()).isZero();
		}

		@Test
		void hideOrUnhideSentGives409() throws Exception {
			FeedbackRecord record = add();
			assertThat(admin(action(record.id(), "send")).getResponse().getStatus()).isEqualTo(200);
			expectError(admin(action(record.id(), "hide")), 409, "already-sent");
			expectError(admin(action(record.id(), "unhide")), 409, "already-sent");
			assertThat(statusOf(record)).isEqualTo("sent");
		}
	}

	// No songrush.admin.key at all (the default): the admin API answers 404 to every header value.
	@Nested
	@SpringBootTest(properties = "songrush.feedback.token=test-token")
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class BlankKey extends Base {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Test
		void blankKeyGives404ForEveryHeaderValue() throws Exception {
			FeedbackRecord record = add();
			expectNotFoundWithoutHint(admin(get("/admin/api/feedback")));
			for (String key : List.of("", " ", "null", "undefined", KEY)) {
				expectNotFoundWithoutHint(admin(get("/admin/api/feedback").header("X-Admin-Key", key)));
				expectNotFoundWithoutHint(admin(bare(record.id(), "hide", "{}").header("X-Admin-Key", key)
						.header("X-Requested-With", XRW)
						.contentType(MediaType.APPLICATION_JSON)));
				expectNotFoundWithoutHint(admin(bare(record.id(), "send", "{}").header("X-Admin-Key", key)
						.header("X-Requested-With", XRW)
						.contentType(MediaType.APPLICATION_JSON)));
			}
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(sender.calls()).isZero();
		}
	}

	// A blank token: send answers 503 github-disabled; hide still works.
	@Nested
	@SpringBootTest(properties = { "songrush.feedback.token=  ", "songrush.admin.key=" + KEY })
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	class NoToken extends Base {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Test
		void noTokenGives503() throws Exception {
			sender.reset();
			FeedbackRecord record = add();
			expectError(admin(action(record.id(), "send")), 503, "github-disabled");
			expectError(admin(action(record.id(), "send", titleBody("edited"))), 503, "github-disabled");
			assertThat(statusOf(record)).isEqualTo("new");
			assertThat(sender.calls()).isZero();
			assertThat(admin(action(record.id(), "hide")).getResponse().getStatus()).isEqualTo(200);
		}
	}

	// The store write fails after GitHub created the issue.
	@Nested
	@SpringBootTest(properties = { "songrush.feedback.token=test-token", "songrush.admin.key=" + KEY })
	@AutoConfigureMockMvc
	@Import(Fakes.class)
	@ExtendWith(OutputCaptureExtension.class)
	class StoreFailureAfterSend extends Base {

		static final Path DIR = FeedbackFiles.newStoreDir();

		@DynamicPropertySource
		static void storeDir(DynamicPropertyRegistry registry) {
			registry.add("songrush.feedback.store-dir", () -> DIR.toString());
		}

		@Test
		void storeFailureAfterSuccessfulSendGives200NotPersisted(CapturedOutput output) throws Exception {
			sender.reset();
			FeedbackRecord record = add();
			FeedbackRecord other = add("idea", "other", "other text");
			MvcResult result;
			FeedbackFiles.breakDir(DIR);
			try {
				result = admin(action(record.id(), "send"));
				assertThat(result.getResponse().getStatus()).isEqualTo(200);
				JsonNode body = json(result);
				assertThat(body.path("status").asString(null)).isEqualTo("sent");
				assertThat(body.path("issueNumber").asInt(-1)).isEqualTo(100);
				assertThat(body.path("issueUrl").asString(null)).isEqualTo(issueUrl(100));
				assertThat(body.path("persisted").isBoolean()).isTrue();
				assertThat(body.path("persisted").asBoolean(true)).isFalse();

				String line = output.getAll().lines().filter(l -> l.contains("ERROR") && l.contains("100"))
						.findFirst().orElse("");
				assertThat(line).as("an ERROR line names issue 100").isNotEmpty();

				// Stays sent in memory: no duplicate issue.
				assertThat(statusOf(record)).isEqualTo("sent");
				expectError(admin(action(record.id(), "send")), 409, "already-sent");
				assertThat(sender.calls()).isEqualTo(1);
			}
			finally {
				FeedbackFiles.restoreDir(DIR);
			}

			// The next successful write persists it.
			assertThat(admin(action(other.id(), "hide")).getResponse().getStatus()).isEqualTo(200);
			FeedbackStore reloaded = new FeedbackStore(DIR, clock);
			FeedbackRecord persisted = reloaded.get(record.id()).orElseThrow();
			assertThat(persisted.status()).isEqualTo("sent");
			assertThat(persisted.issueNumber()).isEqualTo(100);
			assertThat(persisted.issueUrl()).isEqualTo(issueUrl(100));
			assertThat(reloaded.get(other.id()).orElseThrow().status()).isEqualTo("hidden");
		}
	}
}
