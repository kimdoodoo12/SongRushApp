package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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

// Cycle 046 (B-006c) criterion 1: late joiners in the Room on the cycle 036 fakes (fake clock,
// manual scheduler, seeded Random, recording Outbox, real AudioTokens). Room.addPlayer adds a
// pending player (a watcher) who is promoted at the next prepare with 0 points, keeping the chat
// bucket; until then the watcher is not counted, cannot score, and its chat reaches only itself
// until the reveal (docs/cycles/046-late-joiners.md).
class RoomLateJoinTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef DORA = new PlayerRef("p4", "Dora");

	private static final PlayerRef EVE = new PlayerRef("p5", "Eve");

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
					"winner", "coverUrl"),
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
		room = new Room(catalog, tokens, scheduler, clock, new Random(46), outbox, List.of(members));
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

	// A chat that reaches only its sender, as chat{name, text}.
	private static void assertOnlyToSender(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).as("one message, to the sender only").hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", sender.name())
				.containsEntry("text", text);
	}

	// One chat to everyone with the sender's name and the text.
	private static void assertBroadcastChat(List<Sent> sent, PlayerRef sender, String text) {
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", sender.name())
				.containsEntry("text", text);
	}

	private static void assertRateLimited(List<Sent> sent, PlayerRef sender) {
		assertThat(sent).as("only the error to the sender").hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(sender.id());
		assertKeys(sent.get(0).message());
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "rate-limited");
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

	// Sends a chat and returns what it sent.
	private List<Sent> chat(PlayerRef p, String text) {
		int mark = outbox.mark();
		room.chat(p.id(), text);
		return outbox.since(mark);
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

	private int lastPrepareIndex() {
		return (int) num(outbox.last("prepare").message(), "index");
	}

	// From PREPARE: every listed player readies and the song plays.
	private void readyAndPlay(PlayerRef... ps) {
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		ready(lastPrepareIndex(), ps);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// From PLAYING: lets the lead run out (now == startAt).
	private void toStartAt() {
		long left = num(lastStart(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		assertThat(clock.millis()).isEqualTo(num(lastStart(), "startAt"));
	}

	private void advanceToDeadline() {
		long left = num(lastStart(), "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// From REVEAL: the reveal pause ends and the next song is prepared.
	private void toNextPrepare() {
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// Mina, Joon and Hana start a 5-song game and play song 0 (now == startAt).
	private void threePlaySongZero() {
		build(MINA, JOON, HANA);
		room.start(5);
		readyAndPlay(MINA, JOON, HANA);
		toStartAt();
	}

	// Mina and Joon vote to skip (2 of 3 connected): the song ends with no winner.
	private void skipWithMinaAndJoon(int index) {
		assertThat(skip(MINA, index)).isEqualTo(index + ":1/2");
		int mark = outbox.mark();
		room.skip(JOON.id(), index);
		assertThat(types(outbox.since(mark))).containsExactly("skipState", "reveal", "scores");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// "name:score" per row of the last scores message.
	@SuppressWarnings("unchecked")
	private List<String> lastScoreRows() {
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) outbox.last("scores").message().get("scores")) {
			Map<String, Object> r = (Map<String, Object>) o;
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// "rank:name:score" per row of the last gameOver ranking.
	@SuppressWarnings("unchecked")
	private List<String> rankingRows() {
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) outbox.last("gameOver").message().get("ranking")) {
			Map<String, Object> r = (Map<String, Object>) o;
			rows.add(num(r, "rank") + ":" + r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// ---- joining mid-song ----

	@Test
	void aPlayerAddedMidSongIsPendingAndSendsNothing() {
		threePlaySongZero();
		int mark = outbox.mark();
		room.addPlayer(DORA);
		assertThat(outbox.since(mark)).as("addPlayer sends nothing").isEmpty();
		assertThat(room.isPlayer(DORA.id())).as("pending is not a player").isFalse();
		assertThat(room.isPlayer(MINA.id())).isTrue();
		assertThat(room.isPlayer("nobody")).isFalse();
		assertThat(room.scores()).as("no score row while pending").containsOnlyKeys(MINA.id(), JOON.id(),
				HANA.id());
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aPlayerAddedMidSongIsNotCountedInTheSkipVoteAndTheirSkipIsIgnored() {
		threePlaySongZero();
		room.addPlayer(DORA);

		int mark = outbox.mark();
		room.skip(DORA.id(), 0);
		assertThat(outbox.since(mark)).as("a watcher's skip is ignored").isEmpty();

		assertThat(skip(MINA, 0)).as("3 connected players, the watcher not counted").isEqualTo("0:1/2");
		room.skip(DORA.id(), 0);
		mark = outbox.mark();
		room.skip(JOON.id(), 0);
		assertThat(types(outbox.since(mark))).containsExactly("skipState", "reveal", "scores");
		assertThat(skipRow(outbox.since(mark).get(0))).isEqualTo("0:2/2");
	}

	@Test
	void aWatchersReadyIsIgnoredAndNotWaitedFor() {
		build(MINA, JOON);
		room.start(5);
		room.addPlayer(DORA);

		int mark = outbox.mark();
		room.ready(DORA.id(), 0);
		assertThat(outbox.since(mark)).as("a watcher's ready is ignored").isEmpty();
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).as("the watcher's ready does not count").isEqualTo(Room.Phase.PREPARE);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).as("the watcher is not waited for").isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aWatchersCorrectChatReachesOnlyThemBeforeAndAfterStartAtAndDoesNotScore() {
		build(MINA, JOON, HANA);
		room.start(5);
		readyAndPlay(MINA, JOON, HANA);
		room.addPlayer(DORA);
		String title = exactTitle();

		assertThat(clock.millis()).isLessThan(num(lastStart(), "startAt"));
		assertOnlyToSender(chat(DORA, title), DORA, title);

		toStartAt();
		assertOnlyToSender(chat(DORA, title), DORA, title);
		assertThat(room.phase()).as("the song goes on").isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores()).containsOnlyKeys(MINA.id(), JOON.id(), HANA.id());
		assertThat(room.scores().values()).containsOnly(0);

		// A Room player can still win the song.
		// Cycle 080 declared rewrite (D-094): the winning guess is echoed as chat right after correct.
		List<Sent> sent = chat(MINA, title);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(1).message()).containsEntry("name", "Mina").containsEntry("text", title);
		assertThat(sent.get(2).message()).containsEntry("winner", "Mina");
	}

	@Test
	void aWatchersMissAfterStartAtReachesOnlyThem() {
		// Criterion 1's "their miss after startAt is broadcast" contradicts the Interface and the
		// Watcher chat bullet (every watcher chat in PREPARE and PLAYING reaches only its sender,
		// spec review M1); the Interface is followed here.
		threePlaySongZero();
		room.addPlayer(DORA);
		assertOnlyToSender(chat(DORA, "not a title"), DORA, "not a title");
		assertBroadcastChat(chat(MINA, "not a title"), MINA, "not a title");
	}

	@Test
	void aWatcherHasTheirOwnRateLimit() {
		threePlaySongZero();
		room.addPlayer(DORA);
		for (int i = 0; i < 5; i++) {
			assertOnlyToSender(chat(DORA, "miss " + i), DORA, "miss " + i);
		}
		assertRateLimited(chat(DORA, "miss 5"), DORA);
		assertBroadcastChat(chat(MINA, "miss m"), MINA, "miss m");
		// Half a second refills one token.
		scheduler.advance(Duration.ofMillis(500));
		assertOnlyToSender(chat(DORA, "miss 6"), DORA, "miss 6");
		assertRateLimited(chat(DORA, "miss 7"), DORA);
	}

	// ---- watcher chat by phase ----

	@Test
	void inPrepareAWatchersMatchAndMissReachOnlyTheWatcher() {
		build(MINA, JOON);
		room.start(5);
		room.addPlayer(DORA);
		String title = exactTitle();
		assertOnlyToSender(chat(DORA, title), DORA, title);
		assertOnlyToSender(chat(DORA, "not a title"), DORA, "not a title");
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void inPlayingAWatchersMatchAndMissReachOnlyTheWatcherBeforeAndAfterStartAt() {
		build(MINA, JOON, HANA);
		room.start(5);
		readyAndPlay(MINA, JOON, HANA);
		room.addPlayer(DORA);
		String title = exactTitle();
		assertOnlyToSender(chat(DORA, "early miss"), DORA, "early miss");
		assertOnlyToSender(chat(DORA, title), DORA, title);
		toStartAt();
		assertOnlyToSender(chat(DORA, "late miss"), DORA, "late miss");
		assertOnlyToSender(chat(DORA, title), DORA, title);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void inRevealAWatchersChatReachesEveryone() {
		threePlaySongZero();
		room.addPlayer(DORA);
		String title = exactTitle();
		skipWithMinaAndJoon(0);
		assertBroadcastChat(chat(DORA, title), DORA, title);
		assertBroadcastChat(chat(DORA, "after the reveal"), DORA, "after the reveal");
		assertThat(room.isPlayer(DORA.id())).as("still pending in REVEAL").isFalse();
	}

	// ---- promotion ----

	@Test
	void atTheNextPrepareAWatcherBecomesAFullPlayerWithZeroPointsAfterTheOthers() {
		threePlaySongZero();
		room.addPlayer(DORA);
		// Mina wins song 0 before Dora is promoted.
		chat(MINA, exactTitle());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastScoreRows()).as("no row for the watcher").containsExactly("Mina:1", "Joon:0", "Hana:0");

		toNextPrepare();
		assertThat(lastPrepareIndex()).isEqualTo(1);
		assertThat(room.isPlayer(DORA.id())).isTrue();
		assertThat(new ArrayList<>(room.scores().keySet())).as("after the existing players")
				.containsExactly(MINA.id(), JOON.id(), HANA.id(), DORA.id());
		assertThat(room.scores()).containsEntry(DORA.id(), 0).containsEntry(MINA.id(), 1);

		// The song waits for Dora's ready.
		ready(1, MINA, JOON, HANA);
		assertThat(room.phase()).as("waits for the promoted player").isEqualTo(Room.Phase.PREPARE);
		room.ready(DORA.id(), 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// 4 connected players: a majority is 3, and Dora's vote counts.
		assertThat(skip(DORA, 1)).isEqualTo("1:1/3");
		assertThat(skip(DORA, 1)).as("toggled off").isEqualTo("1:0/3");

		// Dora scores from now on.
		toStartAt();
		// Cycle 080 declared rewrite (D-094): the winning guess is echoed as chat right after correct.
		String doraTitle = exactTitle();
		List<Sent> sent = chat(DORA, doraTitle);
		assertThat(types(sent)).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).containsEntry("name", "Dora").containsEntry("index", 1);
		assertThat(sent.get(1).toAll()).isTrue();
		assertThat(sent.get(1).message()).containsEntry("name", "Dora").containsEntry("text", doraTitle);
		assertThat(sent.get(2).message()).containsEntry("winner", "Dora");
		assertThat(lastScoreRows()).containsExactly("Mina:1", "Joon:0", "Hana:0", "Dora:1");

		// The rest of the game runs out on the deadlines; the ranking includes Dora.
		while (true) {
			toNextPrepare();
			readyAndPlay(MINA, JOON, HANA, DORA);
			int index = lastPrepareIndex();
			advanceToDeadline();
			if (index == 4) {
				break;
			}
		}
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(rankingRows()).containsExactly("1:Mina:1", "1:Dora:1", "3:Joon:0", "3:Hana:0");
	}

	@Test
	void twoWatchersArePromotedInTheOrderTheyWereAdded() {
		threePlaySongZero();
		room.addPlayer(EVE);
		room.addPlayer(DORA);
		skipWithMinaAndJoon(0);
		toNextPrepare();
		assertThat(new ArrayList<>(room.scores().keySet()))
				.containsExactly(MINA.id(), JOON.id(), HANA.id(), EVE.id(), DORA.id());
		readyAndPlay(MINA, JOON, HANA, EVE, DORA);
		advanceToDeadline();
		assertThat(lastScoreRows()).containsExactly("Mina:0", "Joon:0", "Hana:0", "Eve:0", "Dora:0");
	}

	// ---- disconnected while pending ----

	@Test
	void aPendingDisconnectChangesNeitherTheReadyWaitNorTheSkipCount() {
		build(MINA, JOON);
		room.start(5);
		room.addPlayer(DORA);
		room.ready(MINA.id(), 0);

		int mark = outbox.mark();
		room.setConnected(DORA.id(), false);
		assertThat(outbox.since(mark)).as("a pending disconnect sends nothing").isEmpty();
		assertThat(room.phase()).as("Joon is still waited for").isEqualTo(Room.Phase.PREPARE);
		room.setConnected(DORA.id(), true);
		room.setConnected(DORA.id(), false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		assertThat(skip(MINA, 0)).as("2 connected players, the watcher not counted").isEqualTo("0:1/2");
		mark = outbox.mark();
		room.setConnected(DORA.id(), true);
		room.setConnected(DORA.id(), false);
		assertThat(outbox.since(mark)).as("no skipState from a pending connection change").isEmpty();
		assertThat(skip(MINA, 0)).isEqualTo("0:0/2");
	}

	@Test
	void aWatcherDisconnectedBeforePromotionIsPromotedDisconnectedAndNotWaitedFor() {
		threePlaySongZero();
		room.addPlayer(DORA);
		room.setConnected(DORA.id(), false);
		skipWithMinaAndJoon(0);
		toNextPrepare();
		assertThat(room.isPlayer(DORA.id())).isTrue();
		assertThat(room.scores()).containsEntry(DORA.id(), 0);

		long now = clock.millis();
		ready(1, MINA, JOON, HANA);
		assertThat(room.phase()).as("the disconnected player is not waited for").isEqualTo(Room.Phase.PLAYING);
		assertThat(num(lastStart(), "serverTime")).as("no prepare timeout").isEqualTo(now);
		// 3 of 4 connected: a majority is 2.
		assertThat(skip(MINA, 1)).isEqualTo("1:1/2");

		// Promoted as disconnected, so ready and skip are ignored until reconnected.
		int mark = outbox.mark();
		room.skip(DORA.id(), 1);
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void aWatcherWhoDisconnectsAndReconnectsBeforePromotionIsPromotedConnected() {
		threePlaySongZero();
		room.addPlayer(DORA);
		room.setConnected(DORA.id(), false);
		room.setConnected(DORA.id(), true);
		skipWithMinaAndJoon(0);
		toNextPrepare();
		ready(1, MINA, JOON, HANA);
		assertThat(room.phase()).as("waits for the connected promoted player").isEqualTo(Room.Phase.PREPARE);
		room.ready(DORA.id(), 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- bucket kept ----

	@Test
	void aWatcherWhoUsedFiveTokensAndIsPromotedAtOnceHasNoTokenLeft() {
		threePlaySongZero();
		room.addPlayer(DORA);
		skipWithMinaAndJoon(0);
		// 1 ms before the reveal pause ends, Dora spends the whole bucket.
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		for (int i = 0; i < 5; i++) {
			assertBroadcastChat(chat(DORA, "word " + i), DORA, "word " + i);
		}
		scheduler.advance(Duration.ofMillis(1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.isPlayer(DORA.id())).isTrue();
		assertRateLimited(chat(DORA, "word 5"), DORA);
	}

	// ---- ignored adds ----

	@Test
	void addingAKnownPlayerDoesNothing() {
		build(MINA, JOON);
		room.start(5);
		int mark = outbox.mark();
		room.addPlayer(new PlayerRef(MINA.id(), "Other"));
		room.addPlayer(JOON);
		assertThat(outbox.since(mark)).isEmpty();
		room.ready(MINA.id(), 0);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		advanceToDeadline();
		toNextPrepare();
		assertThat(new ArrayList<>(room.scores().keySet())).containsExactly(MINA.id(), JOON.id());
		readyAndPlay(MINA, JOON);
		advanceToDeadline();
		assertThat(lastScoreRows()).as("names unchanged, no extra row").containsExactly("Mina:0", "Joon:0");
	}

	@Test
	void addingAnAlreadyPendingIdDoesNothing() {
		threePlaySongZero();
		room.addPlayer(DORA);
		room.addPlayer(DORA);
		room.addPlayer(new PlayerRef(DORA.id(), "Other"));
		skipWithMinaAndJoon(0);
		toNextPrepare();
		assertThat(new ArrayList<>(room.scores().keySet()))
				.containsExactly(MINA.id(), JOON.id(), HANA.id(), DORA.id());
		readyAndPlay(MINA, JOON, HANA, DORA);
		advanceToDeadline();
		assertThat(lastScoreRows()).as("one row, the first name").containsExactly("Mina:0", "Joon:0", "Hana:0",
				"Dora:0");
	}

	@Test
	void addPlayerInLobbyDoesNothing() {
		build(MINA, JOON);
		int mark = outbox.mark();
		room.addPlayer(DORA);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.isPlayer(DORA.id())).isFalse();

		room.start(5);
		assertThat(room.isPlayer(DORA.id())).as("not promoted by the first prepare").isFalse();
		assertThat(room.scores()).containsOnlyKeys(MINA.id(), JOON.id());
		ready(0, MINA, JOON);
		assertThat(room.phase()).as("Dora is not waited for").isEqualTo(Room.Phase.PLAYING);
		assertThat(chat(DORA, "hello")).as("an id the Room does not know is ignored").isEmpty();
	}

	// ---- last song ----

	@Test
	void aPlayerAddedDuringTheLastSongIsNeverPromotedAndNotRanked() {
		build(MINA, JOON);
		room.start(5);
		while (true) {
			readyAndPlay(MINA, JOON);
			if (lastPrepareIndex() == 4) {
				break;
			}
			advanceToDeadline();
			toNextPrepare();
		}
		room.addPlayer(DORA);
		toStartAt();
		chat(MINA, exactTitle());
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(room.isPlayer(DORA.id())).isFalse();
		assertThat(room.scores()).containsOnlyKeys(MINA.id(), JOON.id());
		assertThat(rankingRows()).containsExactly("1:Mina:1", "2:Joon:0");
		assertThat(outbox.ofType("prepare")).hasSize(5);
	}

	// ---- pause ----

	@Test
	void aPlayerAddedWhilePausedIsPromotedByResume() {
		threePlaySongZero();
		room.pause();
		room.addPlayer(DORA);
		assertThat(room.isPlayer(DORA.id())).isFalse();

		int mark = outbox.mark();
		room.resume();
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(lastPrepareIndex()).as("the same song again").isEqualTo(0);
		assertThat(room.isPlayer(DORA.id())).isTrue();
		assertThat(new ArrayList<>(room.scores().keySet()))
				.containsExactly(MINA.id(), JOON.id(), HANA.id(), DORA.id());
		ready(0, MINA, JOON, HANA);
		assertThat(room.phase()).as("waits for the promoted player").isEqualTo(Room.Phase.PREPARE);
		room.ready(DORA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

}
