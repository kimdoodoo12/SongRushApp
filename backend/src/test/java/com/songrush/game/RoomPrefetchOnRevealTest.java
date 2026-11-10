package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 107 (D-119) at Room level, on the cycle 036 fakes: the early mode "prefetch on reveal"
// (docs/cycles/107-prefetch-on-reveal.md, backend items 2-8 and the early-mode Acceptance bullets).
//
// API this test pins (new; the existing constructors stay and mean prefetchOnReveal = false):
//   public Room(Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
//       RandomGenerator random, Outbox outbox, List<PlayerRef> players, CoverMap covers,
//       boolean prefetchOnReveal)
// Nothing else is new: during the reveal of song N (early mode, N not the last) the Room is in
// phase PREPARE with songIndex N + 1, as the spec's "phase=PREPARE, songIndex=next" says.
//
// The current song is found by resolving the token in prepare.audioUrl. GameFixture titles are unique
// per anime, so an exact "en" title identifies exactly one song.
class RoomPrefetchOnRevealTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	private static final long SEED = 107;

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	private static final List<String> DEFAULT_PREPARE_KEYS = List.of("type", "index", "total", "audioUrl");

	private static final List<String> EARLY_PREPARE_KEYS = List.of("type", "index", "total", "audioUrl", "holdMs");

	// Far past any deadline, prepare timeout or reveal pause of the fixture songs.
	private static final Duration LONG = Duration.ofMinutes(10);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private void build(boolean prefetchOnReveal, PlayerRef... members) {
		catalog = GameFixture.build(dir, SEVEN, 0);
		players = List.of(members);
		room = new Room(catalog, tokens, scheduler, clock, new Random(SEED), outbox, players, CoverMap.empty(),
				prefetchOnReveal);
	}

	private void buildEarly() {
		build(true, MINA, JOON);
	}

	// A choice catalog of 30 covered anime, each its own series group (as RoomChoiceTest).
	private void buildChoice(boolean prefetchOnReveal, PlayerRef... members) {
		built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, 30));
		catalog = built.catalog();
		players = List.of(members);
		room = new Room(catalog, tokens, scheduler, clock, new Random(100), outbox, players, built.covers(),
				prefetchOnReveal);
	}

	// ---- helpers ----

	private static long num(Map<String, Object> message, String key) {
		Object value = message.get(key);
		assertThat(value).as(key).isInstanceOf(Number.class);
		return ((Number) value).longValue();
	}

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private Map<String, Object> lastPrepare() {
		return outbox.last("prepare").message();
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	private int themeOf(Map<String, Object> prepare) {
		String url = (String) prepare.get("audioUrl");
		assertThat(url).startsWith("/audio/");
		return tokens.resolve(url.substring("/audio/".length())).orElseThrow();
	}

	private Theme currentTheme() {
		return catalog.theme(themeOf(lastPrepare())).orElseThrow();
	}

	private Anime animeOf(Theme theme) {
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private String titleOf(Theme theme) {
		return animeOf(theme).titles().get("en").get(0);
	}

	private String exactTitle() {
		return titleOf(currentTheme());
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
	}

	// From LOBBY: starts a 5-song game and plays song 0 until PLAYING.
	private void startAndPlay() {
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// From PLAYING: waits for startAt and lets the player answer with the exact title. Returns the
	// clock at the reveal.
	private long answer(PlayerRef winner) {
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		long left = num(lastStart(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		room.chat(winner.id(), exactTitle());
		assertThat(types(outbox.since(mark))).as("answered").contains("correct", "reveal");
		return clock.millis();
	}

	// Start song 0 and let Mina win it; returns the clock at the reveal of song 0.
	private long playSongZeroToReveal() {
		startAndPlay();
		return answer(MINA);
	}

	private static List<String> scoreRows(Map<String, Object> scoresMessage) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<?>) scoresMessage.get("scores")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) row;
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	private void assertEarlyPrepare(Map<String, Object> prepare, int index, long holdMs) {
		assertThat(prepare.keySet()).as("early prepare keys").containsExactlyInAnyOrderElementsOf(EARLY_PREPARE_KEYS);
		assertThat(num(prepare, "index")).isEqualTo(index);
		assertThat(num(prepare, "total")).isEqualTo(5);
		assertThat(num(prepare, "holdMs")).as("holdMs").isEqualTo(holdMs);
	}

	// The draw order of a seeded game on this catalog, found by a default-mode probe room with its
	// own clock, scheduler and outbox (the draw happens at start and uses only the Random).
	private List<Integer> drawOrder() {
		MutableClock probeClock = new MutableClock();
		ManualScheduler probeScheduler = new ManualScheduler(probeClock);
		RecordingOutbox probeOutbox = new RecordingOutbox();
		AudioTokens probeTokens = new AudioTokens(probeClock);
		Room probe = new Room(catalog, probeTokens, probeScheduler, probeClock, new Random(SEED), probeOutbox,
				List.of(MINA, JOON));
		probe.start(5);
		probeScheduler.advance(Duration.ofHours(1));
		assertThat(probeOutbox.ofType("gameOver")).as("probe game finished").hasSize(1);
		List<Integer> order = new ArrayList<>();
		for (Sent s : probeOutbox.ofType("prepare")) {
			String url = (String) s.message().get("audioUrl");
			order.add(probeTokens.resolve(url.substring("/audio/".length())).orElseThrow());
		}
		assertThat(order).hasSize(5);
		return order;
	}

	private static List<JsonNode> leaves(Map<String, Object> message) {
		List<JsonNode> out = new ArrayList<>();
		collect(MAPPER.valueToTree(message), out);
		return out;
	}

	private static void collect(JsonNode node, List<JsonNode> out) {
		if (node.isContainer()) {
			for (JsonNode child : node.values()) {
				collect(child, out);
			}
		}
		else {
			out.add(node);
		}
	}

	// ---- default mode with the new constructor ----

	@Test
	void prefetchOffWithTheNewConstructorIsTodaysBehaviour() {
		build(false, MINA, JOON);
		startAndPlay();
		int mark = outbox.mark();
		answer(MINA);
		assertThat(types(outbox.since(mark))).as("no prepare during the reveal").doesNotContain("prepare");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		readyAll(1);
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(outbox.ofType("prepare")).hasSize(1);
		scheduler.advance(Duration.ofMillis(1));
		Map<String, Object> prepare = lastPrepare();
		assertThat(prepare.keySet()).as("default prepare keys").containsExactlyElementsOf(DEFAULT_PREPARE_KEYS);
		assertThat(num(prepare, "index")).isEqualTo(1);
	}

	// ---- the early prepare ----

	@Test
	void theFirstPrepareOfAGameHasNoHoldMs() {
		buildEarly();
		room.start(5);
		assertThat(outbox.all()).hasSize(1);
		assertThat(lastPrepare().keySet()).containsExactlyElementsOf(DEFAULT_PREPARE_KEYS);
	}

	@Test
	void theNextPrepareGoesOutRightAfterRevealAndScoresWithHoldMs() {
		buildEarly();
		startAndPlay();
		long startAt = num(lastStart(), "startAt");
		scheduler.advance(Duration.ofMillis(startAt - clock.millis()));
		int song0 = themeOf(lastPrepare());
		int mark = outbox.mark();
		room.chat(MINA.id(), exactTitle());
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores", "prepare");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(num(sent.get(2).message(), "index")).isEqualTo(0);
		assertEarlyPrepare(sent.get(4).message(), 1, REVEAL_MS);
		assertThat(themeOf(sent.get(4).message())).as("a new song").isNotEqualTo(song0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
	}

	@Test
	void theEarlyPrepareAlsoFollowsADeadlineAndASkip() {
		buildEarly();
		startAndPlay();
		int mark = outbox.mark();
		long deadlineAt = num(lastStart(), "deadlineAt");
		scheduler.advance(Duration.ofMillis(deadlineAt - clock.millis()));
		assertThat(types(outbox.since(mark))).containsExactly("reveal", "scores", "prepare");
		assertEarlyPrepare(lastPrepare(), 1, REVEAL_MS);

		scheduler.advance(Room.REVEAL_PAUSE);
		readyAll(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		mark = outbox.mark();
		room.skip(MINA.id(), 1);
		room.skip(JOON.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactly("skipState", "skipState", "reveal", "scores", "prepare");
		assertEarlyPrepare(lastPrepare(), 2, REVEAL_MS);
	}

	// ---- start timing ----

	@Test
	void noStartBeforeTheRevealEndsEvenIfEveryoneIsReadyThenStartExactlyAtTheRevealEnd() {
		buildEarly();
		long revealAt = playSongZeroToReveal();
		int mark = outbox.mark();
		readyAll(1);
		assertThat(types(outbox.since(mark))).as("ready during the reveal starts nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(types(outbox.since(mark))).as("no start before the reveal ends").isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("start");
		Map<String, Object> start = lastStart();
		assertThat(num(start, "index")).isEqualTo(1);
		assertThat(num(start, "serverTime")).isEqualTo(revealAt + REVEAL_MS);
		assertThat(num(start, "startAt")).isEqualTo(revealAt + REVEAL_MS + Room.LEAD.toMillis());
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void withSomeoneNotReadyTheSongStartsAtTheRevealEndPlusThePrepareTimeout() {
		buildEarly();
		long revealAt = playSongZeroToReveal();
		int mark = outbox.mark();
		room.ready(MINA.id(), 1);
		scheduler.advance(Room.REVEAL_PAUSE.plus(Room.PREPARE_TIMEOUT).minusMillis(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS + Room.PREPARE_TIMEOUT.toMillis());
	}

	@Test
	void withSomeoneReadyOnlyAfterTheRevealTheSongStartsAtTheirReady() {
		buildEarly();
		long revealAt = playSongZeroToReveal();
		int mark = outbox.mark();
		room.ready(MINA.id(), 1);
		scheduler.advance(Room.REVEAL_PAUSE.plusSeconds(2));
		assertThat(types(outbox.since(mark))).isEmpty();
		room.ready(JOON.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS + 2000);
		// The prepare timeout of the reveal end does not start the song a second time.
		scheduler.advance(Room.PREPARE_TIMEOUT);
		assertThat(outbox.ofType("start").stream().filter(s -> num(s.message(), "index") == 1)).hasSize(1);
	}

	@Test
	void whenTheLastUnreadyPlayerDisconnectsDuringTheRevealStartStillWaitsForTheRevealEnd() {
		buildEarly();
		long revealAt = playSongZeroToReveal();
		int mark = outbox.mark();
		room.ready(MINA.id(), 1);
		scheduler.advance(Duration.ofSeconds(1));
		room.setConnected(JOON.id(), false);
		assertThat(types(outbox.since(mark))).as("no start during the reveal").isEmpty();
		scheduler.advance(Room.REVEAL_PAUSE.minusSeconds(1).minusMillis(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS);
	}

	// ---- chat and skip during the reveal ----

	@Test
	void chatDuringTheRevealIsBroadcastAndNeverScores() {
		buildEarly();
		startAndPlay();
		String song0Title = exactTitle();
		answer(MINA);
		String song1Title = titleOf(currentTheme());
		assertThat(song1Title).isNotEqualTo(song0Title);
		Map<String, Integer> scores = room.scores();
		int mark = outbox.mark();
		room.chat(JOON.id(), song1Title);
		room.chat(JOON.id(), song0Title);
		room.chat(MINA.id(), "just talking");
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat", "chat", "chat");
		assertThat(sent).as("broadcast, not the too-early path").allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).containsEntry("name", "Joon").containsEntry("text", song1Title);
		assertThat(sent.get(1).message()).containsEntry("text", song0Title);
		assertThat(room.scores()).isEqualTo(scores);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		// The song still starts at the reveal end, and is still open to be won.
		readyAll(1);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		answer(JOON);
		assertThat(room.scores()).containsEntry(JOON.id(), 1);
	}

	@Test
	void aChatArrivingAfterTheDeadlineIsBroadcastAndNeverComparedWithTheNextSong() {
		buildEarly();
		List<Integer> order = drawOrder();
		startAndPlay();
		assertThat(themeOf(lastPrepare())).as("probe matches the game").isEqualTo(order.get(0));
		String song1Title = titleOf(catalog.theme(order.get(1)).orElseThrow());
		long deadlineAt = num(lastStart(), "deadlineAt");
		// The clock passes the deadline before the deadline task runs.
		clock.advance(Duration.ofMillis(deadlineAt - clock.millis()));
		int mark = outbox.mark();
		room.chat(JOON.id(), song1Title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("reveal", "scores", "prepare", "chat");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message().get("winner")).isNull();
		assertEarlyPrepare(sent.get(2).message(), 1, REVEAL_MS);
		assertThat(themeOf(sent.get(2).message())).isEqualTo(order.get(1));
		assertThat(sent.get(3).message()).containsEntry("name", "Joon").containsEntry("text", song1Title);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
	}

	@Test
	void skipIsIgnoredDuringTheRevealAndTheNextSongStartsWithNoVotes() {
		buildEarly();
		playSongZeroToReveal();
		int mark = outbox.mark();
		room.skip(MINA.id(), 1);
		room.skip(JOON.id(), 1);
		room.skip(MINA.id(), 0);
		assertThat(types(outbox.since(mark))).isEmpty();
		readyAll(1);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		mark = outbox.mark();
		room.skip(JOON.id(), 1);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertThat(num(sent.get(0).message(), "votes")).isEqualTo(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- the last song ----

	@Test
	void theLastSongEndsWithGameOverAndNoPrepare() {
		buildEarly();
		room.start(5);
		long revealAt = 0;
		for (int k = 0; k < 5; k++) {
			readyAll(k);
			if (k > 0) {
				scheduler.advance(Duration.ofMillis(revealAt + REVEAL_MS - clock.millis()));
			}
			assertThat(room.phase()).as("song %d playing", k).isEqualTo(Room.Phase.PLAYING);
			revealAt = answer(k % 2 == 0 ? MINA : JOON);
		}
		assertThat(outbox.ofType("prepare")).hasSize(5);
		assertThat(types(outbox.since(outbox.all().size() - 2))).containsExactly("reveal", "scores");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("gameOver");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		scheduler.advance(LONG);
		assertThat(outbox.ofType("prepare")).hasSize(5);
	}

	// ---- snapshot, joins and watchers ----

	@Test
	void currentSongDuringTheRevealIsTheNextSongWithoutHoldMs() {
		buildEarly();
		playSongZeroToReveal();
		Map<String, Object> prepare = lastPrepare();
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		Map<String, Object> song = room.currentSong(JOON.id());
		assertThat(song).isNotNull();
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(num(song, "total")).isEqualTo(5);
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(room.currentSong()).isEqualTo(song);
	}

	@Test
	void aJoinDuringTheRevealIsPromotedAtOnceAndGetsItsOwnPrepareWithTheRemainingHold() {
		build(true, MINA, JOON);
		long revealAt = playSongZeroToReveal();
		Map<String, Object> early = lastPrepare();
		scheduler.advance(Duration.ofSeconds(2));
		int mark = outbox.mark();
		room.addPlayer(HANA);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(HANA.id());
		Map<String, Object> own = sent.get(0).message();
		assertEarlyPrepare(own, 1, REVEAL_MS - 2000);
		assertThat(own.get("audioUrl")).isEqualTo(early.get("audioUrl"));
		assertThat(room.isPlayer(HANA.id())).isTrue();
		assertThat(room.scores()).containsEntry(HANA.id(), 0).hasSize(3);

		// Hana's ready counts: everyone ready during the reveal starts the song at the reveal end.
		room.ready(MINA.id(), 1);
		room.ready(JOON.id(), 1);
		room.ready(HANA.id(), 1);
		mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(revealAt + REVEAL_MS - clock.millis()));
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS);
	}

	@Test
	void aJoinerDuringTheRevealIsWaitedFor() {
		build(true, MINA, JOON);
		long revealAt = playSongZeroToReveal();
		room.addPlayer(HANA);
		room.ready(MINA.id(), 1);
		room.ready(JOON.id(), 1);
		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE.plusSeconds(1));
		assertThat(types(outbox.since(mark))).as("Hana is not ready").isEmpty();
		room.ready(HANA.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS + 1000);
	}

	@Test
	void watchersArePromotedAtTheEarlyPrepare() {
		build(true, MINA, JOON);
		startAndPlay();
		room.addPlayer(HANA);
		assertThat(room.isPlayer(HANA.id())).isFalse();
		long revealAt = answer(MINA);
		assertThat(room.isPlayer(HANA.id())).as("promoted with the early prepare").isTrue();
		assertThat(room.scores()).containsEntry(HANA.id(), 0).hasSize(3);
		// The reveal's scores are of song 0, before the promotion.
		assertThat(scoreRows(outbox.last("scores").message())).containsExactly("Mina:1", "Joon:0");
		room.ready(MINA.id(), 1);
		room.ready(JOON.id(), 1);
		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(outbox.since(mark))).as("Hana is waited for").isEmpty();
		room.ready(HANA.id(), 1);
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(revealAt + REVEAL_MS);
	}

	// ---- pause and resume ----

	@Test
	void aPauseDuringTheRevealStopsItAndResumeReprepareWithoutHoldMsThenStartsAtOnceWhenAllReady() {
		buildEarly();
		playSongZeroToReveal();
		Map<String, Object> early = lastPrepare();
		scheduler.advance(Duration.ofSeconds(1));
		readyAll(1);
		room.pause();
		assertThat(room.paused()).isTrue();
		int mark = outbox.mark();
		scheduler.advance(LONG);
		assertThat(types(outbox.since(mark))).as("nothing runs while paused").isEmpty();

		room.resume();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("prepare");
		Map<String, Object> again = sent.get(0).message();
		assertThat(again.keySet()).as("re-prepare keys").containsExactlyElementsOf(DEFAULT_PREPARE_KEYS);
		assertThat(num(again, "index")).isEqualTo(1);
		assertThat(themeOf(again)).isEqualTo(themeOf(early));
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		mark = outbox.mark();
		long now = clock.millis();
		readyAll(1);
		assertThat(types(outbox.since(mark))).as("starts at once, not after the prepare timeout").containsExactly("start");
		assertThat(num(lastStart(), "serverTime")).isEqualTo(now);
	}

	@Test
	void afterResumingFromTheRevealChatIsTheNormalPreparePath() {
		buildEarly();
		playSongZeroToReveal();
		String song1Title = titleOf(currentTheme());
		room.pause();
		room.resume();
		int mark = outbox.mark();
		room.chat(JOON.id(), song1Title);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("chat");
		assertThat(sent.get(0).to()).as("too early: the sender only").isEqualTo(JOON.id());
	}

	// ---- audio token ----

	@Test
	void theEarlyPreparesTokenLivesThroughTheRevealAndTheWholeOldLifetime() {
		buildEarly();
		playSongZeroToReveal();
		String url = (String) lastPrepare().get("audioUrl");
		String token = url.substring("/audio/".length());
		Duration life = Room.REVEAL_PAUSE.plus(Room.PREPARE_TIMEOUT).plus(Room.LEAD).plus(Room.MAX_PLAY)
				.plus(Room.TOKEN_SLACK);
		clock.advance(life.minusMillis(1));
		assertThat(tokens.resolve(token)).as("still valid at reveal + timeout + lead + max play + slack").isPresent();
	}

	// ---- choice and ranked ----

	@SuppressWarnings("unchecked")
	private static List<String> coverUrls(Map<String, Object> carrier) {
		assertThat(carrier).containsKey("choices");
		List<String> urls = new ArrayList<>();
		for (Object choice : (List<Object>) carrier.get("choices")) {
			assertThat(((Map<String, Object>) choice).keySet()).containsExactly("coverUrl");
			urls.add((String) ((Map<String, Object>) choice).get("coverUrl"));
		}
		return urls;
	}

	@Test
	void choiceRoomTheNextPrepareHasChoicesWithNoCorrectMarkAndTheRevealKeepsCorrectChoice() {
		buildChoice(true, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE);
		readyAll(0);
		scheduler.advance(Room.LEAD);
		int a0 = built.animeIndexOfTheme(themeOf(lastPrepare()));
		int correct = coverUrls(lastPrepare()).indexOf(ChoiceFixture.coverUrl(a0));
		assertThat(correct).isNotNegative();
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("correct", "reveal", "scores", "prepare");
		assertThat(num(sent.get(1).message(), "correctChoice")).isEqualTo(correct);
		Map<String, Object> prepare = sent.get(3).message();
		assertThat(prepare.keySet()).containsExactlyInAnyOrder("type", "index", "total", "audioUrl", "choices",
				"holdMs");
		assertThat(num(prepare, "holdMs")).isEqualTo(REVEAL_MS);
		assertThat(num(prepare, "index")).isEqualTo(1);
		int a1 = built.animeIndexOfTheme(themeOf(prepare));
		List<String> urls = coverUrls(prepare);
		assertThat(urls).hasSize(Room.NORMAL_CHOICES).contains(ChoiceFixture.coverUrl(a1));
		// A pick during the reveal does nothing.
		mark = outbox.mark();
		room.pick(JOON.id(), 1, 0);
		assertThat(types(outbox.since(mark))).isEmpty();
		// The snapshot during the reveal carries the next song's choices and no pick.
		Map<String, Object> song = room.currentSong(JOON.id());
		assertThat(coverUrls(song)).isEqualTo(urls);
		assertThat(song.get("picked")).isNull();
	}

	@SuppressWarnings("unchecked")
	@Test
	void rankedTheRankingOfSongNIsInItsRevealAndTheTagsAreClearedForTheNextSong() {
		buildChoice(true, MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		readyAll(0);
		scheduler.advance(Room.LEAD);
		String title0 = ChoiceFixture.title(built.animeIndexOfTheme(themeOf(lastPrepare())));
		int mark = outbox.mark();
		room.chat(MINA.id(), title0);
		room.chat(JOON.id(), title0);
		List<Sent> sent = outbox.since(mark);
		List<String> t = types(sent);
		int reveal = t.indexOf("reveal");
		assertThat(reveal).isPositive();
		assertThat(t.subList(reveal, t.size())).containsExactly("reveal", "scores", "prepare");
		List<String> ranking = new ArrayList<>();
		for (Object row : (List<Object>) sent.get(reveal).message().get("ranking")) {
			Map<String, Object> r = (Map<String, Object>) row;
			ranking.add(r.get("name") + ":" + num(r, "rank") + ":" + num(r, "points"));
		}
		assertThat(ranking).containsExactly("Mina:1:3", "Joon:2:2");
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(prepare.keySet()).as("no correct rows of song 0").containsExactlyInAnyOrderElementsOf(
				EARLY_PREPARE_KEYS);
		Map<String, Object> song = room.currentSong(MINA.id());
		assertThat(song).containsEntry("answered", false);
		assertThat((List<Object>) song.get("correct")).isEmpty();

		readyAll(1);
		scheduler.advance(Room.REVEAL_PAUSE.plus(Room.LEAD));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		String title1 = ChoiceFixture.title(built.animeIndexOfTheme(themeOf(prepare)));
		mark = outbox.mark();
		room.chat(JOON.id(), title1);
		Sent correct = outbox.since(mark).get(0);
		assertThat(correct.type()).isEqualTo("correct");
		assertThat(num(correct.message(), "index")).isEqualTo(1);
		assertThat(num(correct.message(), "rank")).as("Joon is first of song 1").isEqualTo(1);
	}

	// ---- secrecy ----

	@Test
	void nothingBeforeSongNsRevealIdentifiesSongN() {
		buildEarly();
		room.start(5);
		long revealAt = 0;
		for (int k = 0; k < 5; k++) {
			Theme theme = currentTheme();
			Anime anime = animeOf(theme);
			// A messy guess: it matches, but a player's own echoed text is never the exact title.
			String title = "  " + titleOf(theme).toUpperCase().replace(" ", " - ") + "!! ";
			assertThat(catalog.accepted(anime.group())).contains(Normalize.normalize(title));
			room.chat(JOON.id(), title);
			readyAll(k);
			room.chat(MINA.id(), title);
			if (k > 0) {
				scheduler.advance(Duration.ofMillis(revealAt + REVEAL_MS - clock.millis()));
			}
			scheduler.advance(Room.LEAD);
			room.chat(JOON.id(), "a miss");
			if (k % 2 == 0) {
				room.chat(MINA.id(), title);
			}
			else {
				scheduler.advance(Duration.ofMillis(num(lastStart(), "deadlineAt") - clock.millis()));
			}
			revealAt = clock.millis();

			List<Sent> all = outbox.all();
			int revealIndex = -1;
			for (int i = 0; i < all.size(); i++) {
				if ("reveal".equals(all.get(i).type()) && num(all.get(i).message(), "index") == k) {
					revealIndex = i;
				}
			}
			assertThat(revealIndex).as("reveal of song %d", k).isPositive();
			assertThat(all.get(revealIndex + 1).type()).isEqualTo("scores");
			if (k < 4) {
				assertThat(all.get(revealIndex + 2).type()).as("next prepare after reveal and scores")
						.isEqualTo("prepare");
			}

			Set<Long> forbiddenNumbers = Set.of((long) theme.id(), (long) anime.id());
			Set<String> forbiddenStrings = new HashSet<>();
			forbiddenStrings.add(String.valueOf(theme.id()));
			forbiddenStrings.add(String.valueOf(anime.id()));
			forbiddenStrings.add(theme.audioBasename());
			anime.titles().values().forEach(forbiddenStrings::addAll);
			if (anime.reveal() != null) {
				forbiddenStrings.add(anime.reveal());
			}
			if (theme.song() != null) {
				forbiddenStrings.add(theme.song());
			}
			forbiddenStrings.addAll(theme.artists());

			for (Sent s : all.subList(0, revealIndex)) {
				Map<String, Object> message = new LinkedHashMap<>(s.message());
				// holdMs is a duration (5000), not an id; GameFixture theme ids start at 5000.
				message.remove("holdMs");
				for (JsonNode leaf : leaves(message)) {
					if (leaf.isIntegralNumber()) {
						assertThat(forbiddenNumbers).as(s.type() + " number before reveal %d", k)
								.doesNotContain(leaf.longValue());
					}
					else if (leaf.isString()) {
						String value = leaf.stringValue();
						assertThat(forbiddenStrings).as(s.type() + " string before reveal %d", k).doesNotContain(value);
						assertThat(value).doesNotContain(theme.audioBasename());
					}
				}
			}
		}
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(outbox.ofType("gameOver")).hasSize(1);
	}

}
