package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 036 (B-006a) criteria 1-9: the room song state machine on a fake clock, a manual
// scheduler, a seeded Random, a recording Outbox and the real AudioTokens. The current song is
// found by resolving the token in prepare.audioUrl (docs/cycles/036-room-state-machine.md).
class RoomTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	// 5 drawable songs: one shorter and one longer than 90 s.
	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	private static final Map<String, Set<String>> KEYS = Map.of(
			"prepare", Set.of("type", "index", "total", "audioUrl"),
			"start", Set.of("type", "index", "startAt", "deadlineAt", "serverTime"),
			"chat", Set.of("type", "name", "text"),
			"correct", Set.of("type", "index", "name"),
			"error", Set.of("type", "code"),
			"scores", Set.of("type", "scores"),
			"reveal", Set.of("type", "index", "reveal", "titles", "song", "artists", "themeType", "themeSlug",
					"winner"),
			"gameOver", Set.of("type", "ranking"));

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private List<PlayerRef> players;

	private void build(long[] durations, int nullGroup, PlayerRef... members) {
		catalog = GameFixture.build(dir, durations, nullGroup);
		players = List.of(members);
		room = new Room(catalog, tokens, scheduler, clock, new Random(36), outbox, players);
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

	private static void assertKeys(Map<String, Object> message) {
		String type = (String) message.get("type");
		assertThat(KEYS).as("known message type " + type).containsKey(type);
		assertThat(message.keySet()).as("keys of " + type).containsExactlyInAnyOrderElementsOf(KEYS.get(type));
	}

	private Theme currentTheme() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return catalog.theme(themeId).orElseThrow();
	}

	private Anime currentAnime() {
		return catalog.anime(currentTheme().animeId()).orElseThrow();
	}

	// The current song's English title with changed case, spaces and punctuation.
	private String messyGuess() {
		String title = currentAnime().titles().get("en").get(0);
		String guess = "  " + title.toUpperCase().replace(" ", " - ") + "!! ";
		assertThat(guess).isNotEqualTo(title);
		assertThat(catalog.accepted(currentAnime().group())).contains(Normalize.normalize(guess));
		return guess;
	}

	private String exactTitle() {
		return currentAnime().titles().get("en").get(0);
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	private void advanceToDeadline() {
		long left = num(lastStart(), "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left));
	}

	private static List<String> scoreRows(Map<String, Object> scoresMessage) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<?>) scoresMessage.get("scores")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) row;
			assertThat(r.keySet()).containsExactlyInAnyOrder("name", "score");
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	private static List<String> rankingRows(Map<String, Object> gameOver) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<?>) gameOver.get("ranking")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) row;
			assertThat(r.keySet()).containsExactlyInAnyOrder("rank", "name", "score");
			rows.add(num(r, "rank") + ":" + r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// Every leaf value of the message's Jackson tree.
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

	private static boolean carriesString(Map<String, Object> message, String text) {
		return leaves(message).stream().anyMatch(n -> n.isString() && n.stringValue().equals(text));
	}

	// ---- constants ----

	@Test
	void constantsMatchTheSpec() {
		assertThat(Room.PREPARE_TIMEOUT).isEqualTo(Duration.ofSeconds(25));
		assertThat(Room.LEAD).isEqualTo(Duration.ofSeconds(3));
		assertThat(Room.MAX_PLAY).isEqualTo(Duration.ofSeconds(90));
		assertThat(Room.REVEAL_PAUSE).isEqualTo(Duration.ofSeconds(5));
		assertThat(Room.TOKEN_SLACK).isEqualTo(Duration.ofSeconds(30));
		assertThat(Room.MIN_SONGS).isEqualTo(5);
		assertThat(Room.MAX_SONGS).isEqualTo(30);
		assertThat(Room.MAX_CHAT).isEqualTo(200);
	}

	// ---- criterion 1 ----

	@Test
	void fullGameRunsEachSongThroughPrepareReadyStartDeadlineRevealScoresAndPause() {
		build(FIVE, 2, MINA);
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(room.songIndex()).isEqualTo(-1);

		int mark = outbox.mark();
		room.start(5);
		assertThat(types(outbox.since(mark))).containsExactly("prepare");

		Set<Integer> drawn = new HashSet<>();
		for (int k = 0; k < 5; k++) {
			assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
			assertThat(room.songIndex()).isEqualTo(k);
			Sent prepareSent = outbox.last("prepare");
			Map<String, Object> prepare = prepareSent.message();
			assertThat(prepareSent.toAll()).isTrue();
			assertKeys(prepare);
			assertThat(num(prepare, "index")).isEqualTo(k);
			assertThat(num(prepare, "total")).isEqualTo(5);

			Theme theme = currentTheme();
			Anime anime = currentAnime();
			assertThat(anime.group()).as("drawn anime has a group").isNotNull();
			assertThat(drawn.add(theme.id())).as("distinct draw").isTrue();
			long durationMs = catalog.candidate(theme.id()).orElseThrow().durationMs();

			mark = outbox.mark();
			room.start(5);
			assertThat(outbox.since(mark)).as("start in PREPARE is ignored").isEmpty();
			assertThat(room.songIndex()).isEqualTo(k);

			long readyAt = clock.millis();
			room.ready(MINA.id(), k);
			assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
			Sent startSent = outbox.last("start");
			Map<String, Object> start = startSent.message();
			assertThat(startSent.toAll()).isTrue();
			assertKeys(start);
			assertThat(num(start, "index")).isEqualTo(k);
			assertThat(num(start, "serverTime")).isEqualTo(readyAt);
			assertThat(num(start, "startAt")).isEqualTo(readyAt + Room.LEAD.toMillis());
			assertThat(num(start, "deadlineAt") - num(start, "startAt"))
					.isEqualTo(Math.min(durationMs, Room.MAX_PLAY.toMillis()));

			mark = outbox.mark();
			room.start(5);
			assertThat(outbox.since(mark)).as("start in PLAYING is ignored").isEmpty();

			long left = num(start, "deadlineAt") - clock.millis();
			scheduler.advance(Duration.ofMillis(left - 1));
			assertThat(outbox.since(mark)).isEmpty();
			assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
			scheduler.advance(Duration.ofMillis(1));
			List<Sent> ended = outbox.since(mark);
			assertThat(types(ended)).containsExactly("reveal", "scores");
			assertThat(ended).allMatch(Sent::toAll);
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

			Map<String, Object> reveal = ended.get(0).message();
			assertKeys(reveal);
			assertThat(num(reveal, "index")).isEqualTo(k);
			assertThat(reveal.get("reveal")).isEqualTo(anime.reveal());
			assertThat(reveal.get("titles")).isEqualTo(anime.titles());
			assertThat(reveal.get("song")).isEqualTo(theme.song());
			assertThat(reveal.get("artists")).isEqualTo(theme.artists());
			assertThat(reveal.get("themeType")).isEqualTo(theme.type());
			assertThat(reveal.get("themeSlug")).isEqualTo(theme.slug());
			assertThat(reveal.get("winner")).isNull();
			Map<String, Object> scores = ended.get(1).message();
			assertKeys(scores);
			assertThat(scoreRows(scores)).containsExactly("Mina:0");

			mark = outbox.mark();
			room.start(5);
			assertThat(outbox.since(mark)).as("start in REVEAL is ignored").isEmpty();

			scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
			assertThat(outbox.since(mark)).as("nothing before the pause ends").isEmpty();
			scheduler.advance(Duration.ofMillis(1));
			assertThat(types(outbox.since(mark))).containsExactly(k < 4 ? "prepare" : "gameOver");
		}

		assertThat(drawn).containsExactlyInAnyOrderElementsOf(GameFixture.themeIds(0, 5));
		Sent gameOverSent = outbox.last("gameOver");
		assertThat(gameOverSent.toAll()).isTrue();
		assertKeys(gameOverSent.message());
		assertThat(rankingRows(gameOverSent.message())).containsExactly("1:Mina:0");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(room.songIndex()).isEqualTo(-1);
	}

	// ---- criterion 2 ----

	@Test
	void songStartsOnlyWhenEveryPlayerIsReadyForTheCurrentIndex() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		room.ready(MINA.id(), 0);
		room.ready(MINA.id(), 0);
		room.ready("ghost", 0);
		room.ready(JOON.id(), 1);
		room.ready(JOON.id(), -1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("start")).isEmpty();

		scheduler.advance(Room.PREPARE_TIMEOUT.minusMillis(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("start")).isEmpty();

		long now = clock.millis();
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
		assertThat(num(lastStart(), "serverTime")).isEqualTo(now);

		int mark = outbox.mark();
		room.ready(MINA.id(), 0);
		scheduler.advance(Duration.ofMillis(1));
		assertThat(outbox.since(mark)).as("ready in PLAYING and the old prepare timeout do nothing").isEmpty();
	}

	@Test
	void songStartsAtPrepareTimeoutWhenOnlyOnePlayerIsReady() {
		build(FIVE, 0, MINA, JOON);
		long preparedAt = clock.millis();
		room.start(5);
		room.ready(MINA.id(), 0);

		scheduler.advance(Room.PREPARE_TIMEOUT.minusMillis(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		scheduler.advance(Duration.ofMillis(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
		assertThat(num(lastStart(), "serverTime")).isEqualTo(preparedAt + Room.PREPARE_TIMEOUT.toMillis());
		assertThat(num(lastStart(), "index")).isEqualTo(0);
	}

	@Test
	void songStartsAtPrepareTimeoutWhenNobodyIsReady() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		scheduler.advance(Room.PREPARE_TIMEOUT);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
	}

	// ---- criterion 3 ----

	@Test
	void correctAnswerAfterStartAtEndsTheSongAtOnceAndScores() {
		build(FIVE, 0, MINA, JOON);
		int windowStart = outbox.mark();
		room.start(5);
		readyAll(0);
		scheduler.advance(Room.LEAD);
		assertThat(clock.millis()).isEqualTo(num(lastStart(), "startAt"));
		String guess = messyGuess();

		int mark = outbox.mark();
		room.chat(JOON.id(), guess);
		List<Sent> after = outbox.since(mark);
		assertThat(types(after)).containsExactly("correct", "reveal", "scores");
		assertThat(after).allMatch(Sent::toAll);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		Map<String, Object> correct = after.get(0).message();
		assertKeys(correct);
		assertThat(num(correct, "index")).isEqualTo(0);
		assertThat(correct.get("name")).isEqualTo("Joon");

		List<Sent> beforeReveal = outbox.since(windowStart);
		beforeReveal = beforeReveal.subList(0, types(beforeReveal).indexOf("reveal"));
		assertThat(beforeReveal).noneMatch(s -> carriesString(s.message(), guess));

		assertThat(room.scores()).containsEntry(JOON.id(), 1).containsEntry(MINA.id(), 0);
		assertThat(after.get(1).message().get("winner")).isEqualTo("Joon");
		assertThat(scoreRows(after.get(2).message())).containsExactly("Mina:0", "Joon:1");
	}

	@Test
	void matchingChatJustBeforeDeadlineScores() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		long left = num(lastStart(), "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left - 1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		room.chat(MINA.id(), messyGuess());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.scores()).containsEntry(MINA.id(), 1);
		assertThat(outbox.last("reveal").message().get("winner")).isEqualTo("Mina");
	}

	@Test
	void matchingChatAtDeadlineBeforeTheTaskRunsDoesNotScore() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		String guess = messyGuess();
		clock.set(Instant.ofEpochMilli(num(lastStart(), "deadlineAt")));

		int mark = outbox.mark();
		room.chat(JOON.id(), guess);
		List<Sent> after = outbox.since(mark);
		assertThat(types(after)).containsExactly("reveal", "scores", "chat");
		assertThat(after).allMatch(Sent::toAll);
		assertThat(after.get(0).message().get("winner")).isNull();
		assertThat(after.get(0).message()).containsKey("winner");
		Map<String, Object> chat = after.get(2).message();
		assertKeys(chat);
		assertThat(chat).containsEntry("name", "Joon").containsEntry("text", guess);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsEntry(JOON.id(), 0).containsEntry(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		mark = outbox.mark();
		scheduler.advance(Duration.ZERO);
		assertThat(outbox.since(mark)).as("the deadline task does not end the song again").isEmpty();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(1);
	}

	// ---- criterion 4 ----

	@Test
	void staleTimersOfAnEarlierSongDoNotEndTheNextSong() {
		build(new long[] {80000, 80000, 80000, 80000, 80000}, 0, MINA);
		room.start(5);
		room.ready(MINA.id(), 0);
		long oldDeadline = num(lastStart(), "deadlineAt");
		scheduler.advance(Room.LEAD);
		room.chat(MINA.id(), messyGuess());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).as("ready for the old index is ignored").isEqualTo(Room.Phase.PREPARE);
		room.ready(MINA.id(), 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		long newDeadline = num(lastStart(), "deadlineAt");
		assertThat(newDeadline).isGreaterThan(oldDeadline + 1000);

		int mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(oldDeadline + 1000 - clock.millis()));
		assertThat(outbox.since(mark)).as("old deadline and old prepare timeout do nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isEqualTo(1);

		scheduler.advance(Duration.ofMillis(newDeadline - clock.millis() - 1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("reveal", "scores");
		assertThat(num(outbox.last("reveal").message(), "index")).isEqualTo(1);
	}

	// ---- criterion 5 ----

	@Test
	void matchingChatBeforeStartAtReachesOnlyTheSenderAsPlainChat() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		String guess = messyGuess();

		int mark = outbox.mark();
		room.chat(MINA.id(), guess);
		List<Sent> early = outbox.since(mark);
		assertThat(early).hasSize(1);
		assertThat(early.get(0).to()).isEqualTo(MINA.id());
		assertKeys(early.get(0).message());
		assertThat(early.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Mina")
				.containsEntry("text", guess);

		mark = outbox.mark();
		room.chat(MINA.id(), "not the answer at all");
		List<Sent> miss = outbox.since(mark);
		assertThat(miss).hasSize(1);
		assertThat(miss.get(0).toAll()).isTrue();
		assertThat(miss.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Mina")
				.containsEntry("text", "not the answer at all");
		assertThat(early.get(0).message().keySet()).isEqualTo(miss.get(0).message().keySet());

		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));
		scheduler.advance(Room.LEAD.minusMillis(1));
		mark = outbox.mark();
		room.chat(JOON.id(), guess);
		List<Sent> beforeStart = outbox.since(mark);
		assertThat(beforeStart).hasSize(1);
		assertThat(beforeStart.get(0).to()).isEqualTo(JOON.id());
		assertKeys(beforeStart.get(0).message());
		assertThat(beforeStart.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Joon")
				.containsEntry("text", guess);

		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);
		assertThat(outbox.ofType("correct")).isEmpty();

		mark = outbox.mark();
		room.chat(JOON.id(), "still guessing");
		assertThat(outbox.since(mark)).hasSize(1);
		assertThat(outbox.since(mark).get(0).toAll()).isTrue();

		scheduler.advance(Duration.ofMillis(1));
		mark = outbox.mark();
		room.chat(JOON.id(), "wrong after start");
		List<Sent> playingMiss = outbox.since(mark);
		assertThat(playingMiss).hasSize(1);
		assertThat(playingMiss.get(0).toAll()).isTrue();
		assertThat(playingMiss.get(0).message()).containsEntry("name", "Joon")
				.containsEntry("text", "wrong after start");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- criterion 6 ----

	@Test
	void chatInTheLobbyIsBroadcastAndNotJudged() {
		build(FIVE, 0, MINA, JOON);
		int mark = outbox.mark();
		room.chat(MINA.id(), "hello lobby");
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Mina")
				.containsEntry("text", "hello lobby");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	@Test
	void chatFromAnUnknownPlayerIsIgnored() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		scheduler.advance(Room.LEAD);
		int mark = outbox.mark();
		room.chat("ghost", messyGuess());
		room.chat("ghost", "hello");
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void tooLongChatGetsAnErrorToTheSenderOnly() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		scheduler.advance(Room.LEAD);

		int mark = outbox.mark();
		room.chat(MINA.id(), "a".repeat(201));
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(MINA.id());
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "too-long");

		mark = outbox.mark();
		room.chat(MINA.id(), "b".repeat(200));
		sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("text", "b".repeat(200));
	}

	@Test
	void chatThatNormalizesToEmptyIsIgnored() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		scheduler.advance(Room.LEAD);
		int mark = outbox.mark();
		room.chat(MINA.id(), "");
		room.chat(MINA.id(), "   ");
		room.chat(MINA.id(), "!? - ...");
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void answerAfterTheRevealIsBroadcastAsNormalChat() {
		build(FIVE, 0, MINA, JOON);
		room.start(5);
		readyAll(0);
		String title = exactTitle();
		advanceToDeadline();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Joon")
				.containsEntry("text", title);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- criterion 7 ----

	@Test
	void songCountOutsideFiveToThirtyIsRejected() {
		build(FIVE, 2, MINA);
		for (int n : new int[] {4, 31}) {
			int mark = outbox.mark();
			room.start(n);
			List<Sent> sent = outbox.since(mark);
			assertThat(sent).as("start(" + n + ")").hasSize(1);
			assertThat(sent.get(0).toAll()).isTrue();
			assertKeys(sent.get(0).message());
			assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "bad-song-count");
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(room.songIndex()).isEqualTo(-1);
		}
	}

	@Test
	void fewerDrawableCandidatesThanTheCountIsRejected() {
		// 7 candidates, but only 5 have a group.
		build(FIVE, 2, MINA);
		for (int n : new int[] {6, 30}) {
			int mark = outbox.mark();
			room.start(n);
			List<Sent> sent = outbox.since(mark);
			assertThat(sent).as("start(" + n + ")").hasSize(1);
			assertThat(sent.get(0).toAll()).isTrue();
			assertKeys(sent.get(0).message());
			assertThat(sent.get(0).message()).containsEntry("type", "error")
					.containsEntry("code", "not-enough-songs");
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(room.songIndex()).isEqualTo(-1);
		}
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- criterion 8 ----

	@Test
	void rankingUsesCompetitionRanksWithTiesInPlayerOrderAndStartResetsScores() {
		build(SEVEN, 0, MINA, JOON, HANA);
		room.start(7);
		PlayerRef[] winners = {HANA, HANA, JOON, HANA, JOON, JOON, MINA};
		for (int k = 0; k < winners.length; k++) {
			assertThat(room.songIndex()).isEqualTo(k);
			readyAll(k);
			scheduler.advance(Room.LEAD);
			room.chat(winners[k].id(), exactTitle());
			assertThat(outbox.last("correct").message()).containsEntry("name", winners[k].name());
			assertThat(num(outbox.last("correct").message(), "index")).isEqualTo(k);
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(scoreRows(outbox.last("scores").message())).containsExactly("Mina:1", "Joon:3", "Hana:3");
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 3)
				.containsEntry(HANA.id(), 3);
		assertThat(rankingRows(outbox.last("gameOver").message()))
				.containsExactly("1:Joon:3", "1:Hana:3", "3:Mina:1");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0)
				.containsEntry(HANA.id(), 0).hasSize(3);
	}

	// ---- criterion 9 ----

	@Test
	void nothingSentBeforeTheRevealIdentifiesTheSong() {
		build(FIVE, 1, MINA, JOON);
		Set<String> allowed = Set.of("prepare", "start", "chat", "correct", "error", "scores");
		int mark = outbox.mark();
		room.start(5);
		for (int k = 0; k < 5; k++) {
			Theme theme = currentTheme();
			Anime anime = currentAnime();
			String guess = messyGuess();

			room.chat(MINA.id(), guess);
			room.chat(JOON.id(), "x".repeat(201));
			room.chat(JOON.id(), "just guessing");
			readyAll(k);
			room.chat(MINA.id(), guess);
			scheduler.advance(Room.LEAD);
			room.chat(JOON.id(), "another miss");
			if (k % 2 == 0) {
				room.chat(MINA.id(), guess);
			}
			else {
				advanceToDeadline();
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

			List<Sent> window = outbox.since(mark);
			int revealAt = types(window).indexOf("reveal");
			assertThat(revealAt).isPositive();
			window = window.subList(0, revealAt);
			assertThat(window.get(0).type()).isEqualTo("prepare");
			assertThat(num(window.get(0).message(), "index")).isEqualTo(k);

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

			for (Sent s : window) {
				assertThat(allowed).as("type before reveal").contains(s.type());
				assertKeys(s.message());
				for (JsonNode leaf : leaves(s.message())) {
					if (leaf.isIntegralNumber()) {
						assertThat(forbiddenNumbers).as(s.type() + " number").doesNotContain(leaf.longValue());
					}
					else if (leaf.isString()) {
						String value = leaf.stringValue();
						assertThat(forbiddenStrings).as(s.type() + " string").doesNotContain(value);
						assertThat(value).as(s.type() + " string").doesNotContain(theme.audioBasename());
					}
				}
			}

			mark = outbox.mark();
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(outbox.ofType("gameOver")).hasSize(1);
	}

}
