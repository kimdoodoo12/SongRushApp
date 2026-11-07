package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
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

// Cycle 045 (B-006b) criterion 1: multiplayer rounds in the Room on the cycle 036 fakes (fake clock,
// manual scheduler, seeded Random, recording Outbox, real AudioTokens): the ready rule over
// connected players, the skip vote, the chat token bucket (capacity 5, 2 per second) and chat before
// startAt reaching only its sender (docs/cycles/045-multiplayer-rounds.md).
class RoomRoundsTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef DORA = new PlayerRef("p4", "Dora");

	// 5 drawable songs, each at least 60 s long.
	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	private static final Map<String, Set<String>> KEYS = Map.of(
			"prepare", Set.of("type", "index", "total", "audioUrl"),
			"start", Set.of("type", "index", "startAt", "deadlineAt", "serverTime"),
			"chat", Set.of("type", "name", "text"),
			"correct", Set.of("type", "index", "name"),
			"error", Set.of("type", "code"),
			"scores", Set.of("type", "scores"),
			"skipState", Set.of("type", "index", "votes", "needed"),
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

	private void build(PlayerRef... members) {
		catalog = GameFixture.build(dir, FIVE, 0);
		room = new Room(catalog, tokens, scheduler, clock, new Random(45), outbox, List.of(members));
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

	// "index:votes/needed" of a skipState, after checking it goes to everyone with exactly its keys.
	private static String skipRow(Sent s) {
		assertThat(s.type()).isEqualTo("skipState");
		assertThat(s.toAll()).as("skipState goes to everyone").isTrue();
		assertKeys(s.message());
		return num(s.message(), "index") + ":" + num(s.message(), "votes") + "/" + num(s.message(), "needed");
	}

	// Calls skip and returns the single skipState it sent.
	private String skip(PlayerRef p, int index) {
		int mark = outbox.mark();
		room.skip(p.id(), index);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).as("skip by " + p.name()).containsExactly("skipState");
		return skipRow(sent.get(0));
	}

	private void assertRateLimited(List<Sent> sent, PlayerRef sender) {
		assertThat(sent).as("only the error to the sender").hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "rate-limited");
	}

	// A miss after startAt: one chat to everyone with the sender's name and the text.
	private void assertBroadcastChat(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", sender.name())
				.containsEntry("text", text);
	}

	// A chat that reaches only its sender.
	private void assertOnlyToSender(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", sender.name())
				.containsEntry("text", text);
	}

	private Anime currentAnime() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private String exactTitle() {
		return currentAnime().titles().get("en").get(0);
	}

	private void ready(int index, PlayerRef... ps) {
		for (PlayerRef p : ps) {
			room.ready(p.id(), index);
		}
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	// start(5), every listed player ready for song 0, then the lead runs out (now == startAt).
	private void playAfterStartAt(PlayerRef... ps) {
		room.start(5);
		ready(0, ps);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
		assertThat(clock.millis()).isEqualTo(num(lastStart(), "startAt"));
	}

	private void advanceToDeadline() {
		long left = num(lastStart(), "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left));
	}

	private void assertSkippedWithNoWinner(List<Sent> sent, String lastSkipRow) {
		assertThat(types(sent)).containsExactly("skipState", "reveal", "scores");
		assertThat(skipRow(sent.get(0))).isEqualTo(lastSkipRow);
		Map<String, Object> reveal = sent.get(1).message();
		assertThat(sent.get(1).toAll()).isTrue();
		assertKeys(reveal);
		assertThat(reveal).containsKey("winner");
		assertThat(reveal.get("winner")).isNull();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(outbox.ofType("correct")).isEmpty();
	}

	// ---- ready over connected players ----

	@Test
	void afterADisconnectInPrepareTheOtherTwoReadyStartTheSongWithoutTheTimeout() {
		build(MINA, JOON, HANA);
		room.start(5);
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		assertThat(outbox.since(mark)).as("a disconnect with no votes sends nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		long now = clock.millis();
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
		assertThat(num(lastStart(), "serverTime")).isEqualTo(now);
	}

	@Test
	void aDisconnectThatLeavesEveryConnectedPlayerReadyStartsTheSongAtOnce() {
		build(MINA, JOON, HANA);
		room.start(5);
		ready(0, MINA, JOON);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		long now = clock.millis();
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("start");
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(num(sent.get(0).message(), "serverTime")).isEqualTo(now);
		assertThat(num(sent.get(0).message(), "index")).isEqualTo(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aPlayerWhoReconnectsInPrepareIsCountedAgain() {
		build(MINA, JOON, HANA);
		room.start(5);
		room.setConnected(HANA.id(), false);
		room.setConnected(HANA.id(), true);
		ready(0, MINA, JOON);
		assertThat(room.phase()).as("Hana is counted again and not ready").isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("start")).isEmpty();
		room.ready(HANA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
	}

	@Test
	void aDisconnectRemovesThePlayersReadyMark() {
		build(MINA, JOON);
		room.start(5);
		room.ready(MINA.id(), 0);
		room.setConnected(MINA.id(), false);
		room.setConnected(MINA.id(), true);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).as("Mina's ready was removed by the disconnect").isEqualTo(Room.Phase.PREPARE);
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void withNoConnectedPlayerNothingStarts() {
		build(MINA, JOON);
		room.start(5);
		room.ready(MINA.id(), 0);
		int mark = outbox.mark();
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void setConnectedIgnoresAnUnknownId() {
		build(MINA, JOON);
		room.start(5);
		room.ready(MINA.id(), 0);
		int mark = outbox.mark();
		room.setConnected("ghost", false);
		room.setConnected("ghost", true);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- skip ----

	@Test
	void twoOfThreeVotesSkipTheSongWithNoWinner() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);

		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		int mark = outbox.mark();
		room.skip(JOON.id(), 0);
		assertSkippedWithNoWinner(outbox.since(mark), "0:2/2");
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0)
				.containsEntry(HANA.id(), 0);

		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(outbox.since(mark)).as("nothing during the reveal pause").isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(1);
	}

	@Test
	void aSkipInPlayingBeforeStartAtCounts() {
		build(MINA, JOON, HANA);
		room.start(5);
		ready(0, MINA, JOON, HANA);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));

		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		int mark = outbox.mark();
		room.skip(HANA.id(), 0);
		assertSkippedWithNoWinner(outbox.since(mark), "0:2/2");
	}

	@Test
	void votingAgainTogglesTheVoteOff() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);

		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		assertThat(skip(MINA, 0)).isEqualTo("0:0/2");
		assertThat(skip(JOON, 0)).isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		assertSkippedWithNoWinner(outbox.since(mark), "0:2/2");
	}

	@Test
	void aSkipInLobbyPrepareOrRevealIsIgnored() {
		build(MINA, JOON, HANA);
		int mark = outbox.mark();
		room.skip(MINA.id(), -1);
		room.skip(MINA.id(), 0);
		assertThat(outbox.since(mark)).as("skip in LOBBY").isEmpty();

		room.start(5);
		mark = outbox.mark();
		room.skip(MINA.id(), 0);
		assertThat(outbox.since(mark)).as("skip in PREPARE").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		ready(0, MINA, JOON, HANA);
		scheduler.advance(Room.LEAD);
		assertThat(skip(JOON, 0)).as("the PREPARE vote was not counted").isEqualTo("0:1/2");

		advanceToDeadline();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		room.skip(HANA.id(), 0);
		room.skip(MINA.id(), 0);
		assertThat(outbox.since(mark)).as("skip in REVEAL").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void aSkipWithAWrongIndexOrFromAnUnknownOrDisconnectedPlayerIsIgnored() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);

		int mark = outbox.mark();
		room.skip(MINA.id(), 1);
		room.skip(MINA.id(), -1);
		room.skip("ghost", 0);
		assertThat(outbox.since(mark)).isEmpty();

		room.setConnected(HANA.id(), false);
		assertThat(outbox.since(mark)).as("a disconnect with no votes sends nothing").isEmpty();
		room.skip(HANA.id(), 0);
		assertThat(outbox.since(mark)).as("skip from a disconnected player").isEmpty();

		// Two connected players: needed is 2, and none of the ignored skips counted.
		assertThat(skip(JOON, 0)).isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		mark = outbox.mark();
		room.skip(MINA.id(), 0);
		assertSkippedWithNoWinner(outbox.since(mark), "0:2/2");
	}

	@Test
	void aSkipWhilePausedIsIgnored() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);
		room.pause();

		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		ready(0, MINA, JOON, HANA);
		scheduler.advance(Room.LEAD);
		assertThat(skip(HANA, 0)).as("the paused votes were not counted").isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void clearSkipVoteRemovesAVoteAndResendsSkipStateAndOtherwiseSendsNothing() {
		build(MINA, JOON, HANA);
		room.start(5);
		int mark = outbox.mark();
		room.clearSkipVote(MINA.id());
		assertThat(outbox.since(mark)).as("no vote in PREPARE").isEmpty();

		ready(0, MINA, JOON, HANA);
		scheduler.advance(Room.LEAD);
		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");

		mark = outbox.mark();
		room.clearSkipVote(MINA.id());
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertThat(skipRow(sent.get(0))).isEqualTo("0:0/2");

		mark = outbox.mark();
		room.clearSkipVote(MINA.id());
		room.clearSkipVote(JOON.id());
		room.clearSkipVote("ghost");
		assertThat(outbox.since(mark)).as("no vote to clear").isEmpty();

		assertThat(skip(JOON, 0)).as("Mina's vote is gone").isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void votesResetOnTheNextSong() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);
		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");

		room.chat(JOON.id(), exactTitle());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(outbox.last("reveal").message().get("winner")).isEqualTo("Joon");

		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
		ready(1, MINA, JOON, HANA);
		scheduler.advance(Room.LEAD);

		int mark = outbox.mark();
		room.skip(HANA.id(), 0);
		assertThat(outbox.since(mark)).as("skip for the old index").isEmpty();
		assertThat(skip(HANA, 1)).as("Mina's vote on song 0 is gone").isEqualTo("1:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void withFourPlayersTwoVotesAreNotEnoughUntilANonVoterDisconnects() {
		build(MINA, JOON, HANA, DORA);
		playAfterStartAt(MINA, JOON, HANA, DORA);
		assertThat(skip(MINA, 0)).isEqualTo("0:1/3");
		assertThat(skip(JOON, 0)).isEqualTo("0:2/3");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		int mark = outbox.mark();
		room.setConnected(DORA.id(), false);
		assertSkippedWithNoWinner(outbox.since(mark), "0:2/2");
	}

	@Test
	void aVoterWhoDisconnectsLosesTheVoteAndTheNewSkipStateIsSent() {
		build(MINA, JOON, HANA, DORA);
		playAfterStartAt(MINA, JOON, HANA, DORA);
		assertThat(skip(MINA, 0)).isEqualTo("0:1/3");
		assertThat(skip(JOON, 0)).isEqualTo("0:2/3");

		int mark = outbox.mark();
		room.setConnected(MINA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertThat(skipRow(sent.get(0))).isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// Back to four connected: needed is 3 again and Mina's old vote does not return.
		room.setConnected(MINA.id(), true);
		mark = outbox.mark();
		room.skip(HANA.id(), 0);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("skipState");
		assertThat(skipRow(sent.get(0))).isEqualTo("0:2/3");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		mark = outbox.mark();
		room.skip(DORA.id(), 0);
		assertSkippedWithNoWinner(outbox.since(mark), "0:3/3");
	}

	// ---- chat rate limit ----

	@Test
	void fiveChatsInAnInstantGoThroughAndTheSixthIsRateLimitedToTheSenderOnly() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		for (int i = 1; i <= 5; i++) {
			int mark = outbox.mark();
			room.chat(MINA.id(), "miss " + i);
			assertBroadcastChat(outbox.since(mark), MINA, "miss " + i);
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), "miss 6");
		assertRateLimited(outbox.since(mark), MINA);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void theRefillCarriesItsFractionOver() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		for (int i = 1; i <= 5; i++) {
			room.chat(MINA.id(), "miss " + i);
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), "too fast");
		assertRateLimited(outbox.since(mark), MINA);

		scheduler.advance(Duration.ofMillis(300));
		mark = outbox.mark();
		room.chat(MINA.id(), "after 300 ms");
		assertRateLimited(outbox.since(mark), MINA);

		scheduler.advance(Duration.ofMillis(300));
		mark = outbox.mark();
		room.chat(MINA.id(), "after 600 ms");
		assertBroadcastChat(outbox.since(mark), MINA, "after 600 ms");

		mark = outbox.mark();
		room.chat(MINA.id(), "right after");
		assertRateLimited(outbox.since(mark), MINA);
	}

	@Test
	void theBucketHoldsAtMostFiveTokens() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		scheduler.advance(Duration.ofSeconds(10));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		for (int i = 1; i <= 5; i++) {
			int mark = outbox.mark();
			room.chat(MINA.id(), "miss " + i);
			assertBroadcastChat(outbox.since(mark), MINA, "miss " + i);
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), "miss 6");
		assertRateLimited(outbox.since(mark), MINA);
	}

	@Test
	void aSkipSpendsAChatTokenAndWithoutOneIsRateLimitedAndNotCounted() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);
		for (int i = 1; i <= 5; i++) {
			room.chat(MINA.id(), "miss " + i);
		}
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		// Cycle 050: the refusal of a skip is tagged for the skip vote.
		List<Sent> refused = outbox.since(mark);
		assertThat(refused).as("only the error to the sender").hasSize(1);
		assertThat(refused.get(0).to()).isEqualTo(MINA.id());
		assertThat(refused.get(0).message())
				.isEqualTo(Map.of("type", "error", "code", "rate-limited", "for", "skipVote"));
		assertThat(skip(JOON, 0)).as("Mina's skip was not counted").isEqualTo("0:1/2");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void skipsUseUpTheSameBucketAsChat() {
		build(MINA, JOON, HANA);
		playAfterStartAt(MINA, JOON, HANA);
		// Five toggles spend all five tokens; the sixth toggle finds none.
		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		assertThat(skip(MINA, 0)).isEqualTo("0:0/2");
		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		assertThat(skip(MINA, 0)).isEqualTo("0:0/2");
		assertThat(skip(MINA, 0)).isEqualTo("0:1/2");
		int mark = outbox.mark();
		room.chat(MINA.id(), "one more");
		assertRateLimited(outbox.since(mark), MINA);
	}

	@Test
	void aCorrectAnswerWithoutATokenDoesNotScoreAndTheSongKeepsPlaying() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		for (int i = 1; i <= 5; i++) {
			room.chat(MINA.id(), "miss " + i);
		}
		String title = exactTitle();
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		assertRateLimited(outbox.since(mark), MINA);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);

		scheduler.advance(Duration.ofMillis(500));
		mark = outbox.mark();
		room.chat(MINA.id(), title);
		// Cycle 080 declared rewrite (D-094): the winning guess is echoed as chat right after correct.
		assertThat(types(outbox.since(mark))).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(room.scores()).containsEntry(MINA.id(), 1);
	}

	@Test
	void eachPlayerHasTheirOwnBucket() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		for (int i = 1; i <= 5; i++) {
			room.chat(MINA.id(), "mina " + i);
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), "mina 6");
		assertRateLimited(outbox.since(mark), MINA);

		for (int i = 1; i <= 5; i++) {
			mark = outbox.mark();
			room.chat(JOON.id(), "joon " + i);
			assertBroadcastChat(outbox.since(mark), JOON, "joon " + i);
		}
		mark = outbox.mark();
		room.chat(JOON.id(), "joon 6");
		assertRateLimited(outbox.since(mark), JOON);
	}

	@Test
	void nullInputSpendsNoTokenButATooLongChatDoes() {
		build(MINA, JOON);
		playAfterStartAt(MINA, JOON);
		int mark = outbox.mark();
		for (int i = 0; i < 10; i++) {
			room.chat(MINA.id(), null);
		}
		assertThat(outbox.since(mark)).isEmpty();
		for (int i = 1; i <= 5; i++) {
			mark = outbox.mark();
			room.chat(MINA.id(), "x".repeat(201));
			List<Sent> sent = outbox.since(mark);
			assertThat(sent).hasSize(1);
			assertThat(sent.get(0).to()).isEqualTo(MINA.id());
			assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "too-long");
		}
		mark = outbox.mark();
		room.chat(MINA.id(), "x".repeat(201));
		assertRateLimited(outbox.since(mark), MINA);
	}

	// ---- before startAt ----

	@Test
	void beforeStartAtAMatchAndAMissReachOnlyTheSenderAndFromStartAtAMissReachesEveryone() {
		build(MINA, JOON);
		room.start(5);
		String title = exactTitle();

		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		assertOnlyToSender(outbox.since(mark), MINA, title);
		mark = outbox.mark();
		room.chat(MINA.id(), "a miss in prepare");
		assertOnlyToSender(outbox.since(mark), MINA, "a miss in prepare");

		ready(0, MINA, JOON);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD.minusMillis(1));
		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertOnlyToSender(outbox.since(mark), JOON, title);
		mark = outbox.mark();
		room.chat(JOON.id(), "a miss before startAt");
		assertOnlyToSender(outbox.since(mark), JOON, "a miss before startAt");

		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		scheduler.advance(Duration.ofMillis(1));
		mark = outbox.mark();
		room.chat(JOON.id(), "a miss after startAt");
		assertBroadcastChat(outbox.since(mark), JOON, "a miss after startAt");
	}

	// ---- no leaks ----

	@Test
	void everyMessageOfASkippedSongHasOnlyItsAllowedKeys() {
		build(MINA, JOON, HANA);
		int mark = outbox.mark();
		playAfterStartAt(MINA, JOON, HANA);
		room.skip(MINA.id(), 0);
		room.skip(MINA.id(), 0);
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).contains("skipState", "reveal");
		for (Sent s : sent) {
			assertKeys(s.message());
		}
		assertThat(outbox.last("skipState").message().keySet()).containsExactlyInAnyOrder("type", "index",
				"votes", "needed");
		assertThat(outbox.last("reveal").message().keySet()).containsExactlyInAnyOrder("type", "index", "reveal",
				"titles", "song", "artists", "themeType", "themeSlug", "winner");
	}

}
