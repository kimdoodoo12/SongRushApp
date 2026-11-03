package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 046 (B-006c) criterion 2: joining a running game on the cycle 036 fakes (fake clock, manual
// scheduler, seeded Random, recording Outbox, real AudioTokens). A late joiner (or a member in grace
// at start who reconnects) watches the current song and becomes a Room player at the next prepare.
// As in LobbyTest, every message must reach the outbox as a toPlayer
// (docs/cycles/046-late-joiners.md).
class LobbyLateJoinTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	// Message types that show the game moving on.
	private static final List<String> GAME_TYPES = List.of("start", "reveal", "scores", "prepare", "gameOver");

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private final AtomicInteger closedCalls = new AtomicInteger();

	private Catalog catalog;

	private Lobby lobby;

	private void build() {
		catalog = GameFixture.build(dir, SEVEN, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(46), outbox,
				closedCalls::incrementAndGet);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
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

	private List<Sent> toSince(int mark, String playerId) {
		return outbox.since(mark).stream().filter(s -> playerId.equals(s.to())).toList();
	}

	private List<Sent> gameSince(int mark) {
		return outbox.since(mark).stream().filter(s -> GAME_TYPES.contains(s.type())).toList();
	}

	private Map<String, Object> lastTo(String playerId) {
		List<Sent> sent = toSince(0, playerId);
		assertThat(sent).as("messages to " + playerId).isNotEmpty();
		return sent.get(sent.size() - 1).message();
	}

	private Map<String, Object> lastRosterTo(String playerId) {
		List<Sent> rosters = toSince(0, playerId).stream().filter(s -> "roster".equals(s.type())).toList();
		assertThat(rosters).as("rosters to " + playerId).isNotEmpty();
		return rosters.get(rosters.size() - 1).message();
	}

	private void assertLastError(String playerId, String code) {
		Map<String, Object> last = lastTo(playerId);
		assertThat(last.get("type")).as("last message to " + playerId).isEqualTo("error");
		assertThat(last.get("code")).as("error code to " + playerId).isEqualTo(code);
	}

	// "name:score:host:connected" per player, after checking the allow-listed keys.
	@SuppressWarnings("unchecked")
	private static List<String> rosterRows(Map<String, Object> roster) {
		assertThat(roster.keySet()).as("roster keys").containsExactlyInAnyOrder("type", "phase", "players");
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) roster.get("players")) {
			Map<String, Object> p = (Map<String, Object>) o;
			assertThat(p.keySet()).as("player row keys").containsExactlyInAnyOrder("name", "score", "host",
					"connected");
			rows.add(p.get("name") + ":" + num(p, "score") + ":" + p.get("host") + ":" + p.get("connected"));
		}
		return rows;
	}

	// "name:score" per row of a list of {name, score} rows.
	@SuppressWarnings("unchecked")
	private static List<String> scoreRows(Object scores) {
		assertThat(scores).as("scores").isInstanceOf(List.class);
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) scores) {
			Map<String, Object> r = (Map<String, Object>) o;
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// "rank:name:score" per row of a gameOver ranking.
	@SuppressWarnings("unchecked")
	private static List<String> rankingRows(Map<String, Object> gameOver) {
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) gameOver.get("ranking")) {
			Map<String, Object> r = (Map<String, Object>) o;
			rows.add(num(r, "rank") + ":" + r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// The snapshot with exactly the specified keys, after checking it never carries a player id.
	private static Map<String, Object> assertSnapshot(Map<String, Object> snapshot) {
		assertThat(snapshot.get("type")).isEqualTo("snapshot");
		assertThat(snapshot.keySet()).as("snapshot keys").containsExactlyInAnyOrder("type", "phase", "players",
				"song", "scores");
		assertThat(MAPPER.writeValueAsString(snapshot)).as("no player id").doesNotContain("pid-");
		return snapshot;
	}

	// Asserts the player got exactly joined{code, name} then snapshot first after the mark, and
	// returns the snapshot.
	private Map<String, Object> assertJoinedThenSnapshot(int mark, String playerId, String name) {
		List<Sent> sent = toSince(mark, playerId);
		assertThat(sent.size()).as("messages to " + playerId).isGreaterThanOrEqualTo(2);
		assertThat(types(sent.subList(0, 2))).containsExactly("joined", "snapshot");
		Map<String, Object> joined = sent.get(0).message();
		assertThat(joined.keySet()).containsExactlyInAnyOrder("type", "code", "name");
		assertThat(joined).containsEntry("code", CODE).containsEntry("name", name);
		return assertSnapshot(sent.get(1).message());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> song(Map<String, Object> snapshot) {
		Object song = snapshot.get("song");
		assertThat(song).as("snapshot song").isInstanceOf(Map.class);
		return (Map<String, Object>) song;
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

	private String exactTitle() {
		return currentAnime().titles().get("en").get(0);
	}

	private int lastPrepareIndex() {
		return (int) num(outbox.last("prepare").message(), "index");
	}

	private void ready(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
	}

	// Mina and Joon start a 5-song game and reach PLAYING of song 0.
	private void twoPlaySongZero() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// From PLAYING: both listed players vote to skip; with 2 connected Room players that ends the song.
	private void skipBy(String... ids) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		int index = lastPrepareIndex();
		for (String id : ids) {
			lobby.skip(id, index);
		}
		assertThat(lobby.phase()).as("the skip passed").isEqualTo(Room.Phase.REVEAL);
	}

	// From REVEAL: the reveal pause ends and the next song is prepared.
	private void toNextPrepare() {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// From PLAYING: waits for startAt and lets the winner answer with the exact title.
	private void answer(String winner) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		lobby.chat(winner, exactTitle());
		assertThat(types(outbox.since(mark))).as("answered").contains("correct");
	}

	// ---- joining mid-game ----

	@Test
	void aJoinDuringAGameGetsJoinedSnapshotAndRosterThenPlaysFromTheNextSong() {
		build();
		twoPlaySongZero();
		Map<String, Object> prepare = outbox.last("prepare").message();
		Map<String, Object> start = outbox.last("start").message();

		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing");
		Map<String, Object> song = song(snapshot);
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt");
		assertThat(num(song, "index")).isEqualTo(0);
		assertThat(num(song, "total")).isEqualTo(5);
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(num(song, "startAt")).isEqualTo(num(start, "startAt"));
		assertThat(num(song, "deadlineAt")).isEqualTo(num(start, "deadlineAt"));
		assertThat(scoreRows(snapshot.get("scores"))).as("not in scores until promoted")
				.containsExactly("Mina:0", "Joon:0");

		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");
		assertThat(types(toSince(mark, P1))).containsExactly("roster");
		assertThat(types(toSince(mark, P2))).containsExactly("roster");
		for (String id : List.of(P1, P2, P3)) {
			Map<String, Object> roster = lastRosterTo(id);
			assertThat(roster).containsEntry("phase", "playing");
			assertThat(rosterRows(roster)).containsExactly("Mina:0:true:true", "Joon:0:false:true",
					"Hana:0:false:true");
		}
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		// A watcher's skip is ignored: the two Room players' votes still end the song.
		mark = outbox.mark();
		lobby.skip(P3, 0);
		assertThat(outbox.since(mark)).isEmpty();
		skipBy(P1, P2);
		toNextPrepare();
		assertThat(lastPrepareIndex()).isEqualTo(1);
		assertThat(outbox.all().stream()
				.filter(s -> P3.equals(s.to()) && "prepare".equals(s.type()))
				.toList()).as("the watcher gets the next prepare").hasSize(1);

		// Hana is a Room player now: her ready is waited for and her answer scores.
		ready(1, P1, P2);
		assertThat(lobby.phase()).as("waits for Hana's ready").isEqualTo(Room.Phase.PREPARE);
		lobby.ready(P3, 1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		answer(P3);
		assertThat(outbox.last("correct").message()).containsEntry("name", "Hana");
		assertThat(scoreRows(outbox.last("scores").message().get("scores")))
				.containsExactly("Mina:0", "Joon:0", "Hana:1");
	}

	// ---- room full ----

	@Test
	void theNinthMemberGetsRoomFullDuringAGameUntilOneOfTheEightLeaves() {
		build();
		for (int i = 1; i <= Lobby.MAX_PLAYERS; i++) {
			String id = "pid-" + i;
			assertThat(lobby.join(id, "Name" + i)).isEqualTo("Name" + i);
		}
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		int mark = outbox.mark();
		assertThat(lobby.join("pid-9", "Ivy")).isNull();
		assertThat(outbox.since(mark)).as("only the error to the joiner").hasSize(1);
		assertLastError("pid-9", "room-full");
		assertThat(lobby.join("pid-9", "")).as("the nickname is checked before room-full").isNull();
		assertLastError("pid-9", "bad-nickname");

		// A member in grace still holds the seat.
		lobby.disconnect("pid-8");
		assertThat(lobby.join("pid-9", "Ivy")).isNull();
		assertLastError("pid-9", "room-full");

		// A member who left no longer counts during the game.
		lobby.leave("pid-2");
		mark = outbox.mark();
		assertThat(lobby.join("pid-9", "Ivy")).isEqualTo("Ivy");
		assertThat(types(toSince(mark, "pid-9")).subList(0, 2)).containsExactly("joined", "snapshot");
		assertThat(lobby.names()).as("the leaver stays until the return").hasSize(9).contains("Name2", "Ivy");

		// A member whose grace ran out no longer counts either.
		assertThat(lobby.join("pid-10", "Jay")).isNull();
		assertLastError("pid-10", "room-full");
		scheduler.advance(Lobby.GRACE);
		assertThat(lobby.join("pid-10", "Jay")).isEqualTo("Jay");
		assertThat(lobby.names()).hasSize(10);
	}

	// ---- leaves or drops before promotion ----

	@Test
	void aWatcherWhoLeavesBeforeTheNextSongIsNotWaitedFor() {
		build();
		twoPlaySongZero();
		lobby.join(P3, "Hana");
		lobby.leave(P3);
		skipBy(P1, P2);
		toNextPrepare();

		long now = clock.millis();
		int mark = outbox.mark();
		ready(1, P1, P2);
		assertThat(lobby.phase()).as("starts on the Room players' ready").isEqualTo(Room.Phase.PLAYING);
		assertThat(types(gameSince(mark))).containsExactly("start", "start");
		assertThat(num(outbox.last("start").message(), "serverTime")).as("no prepare timeout").isEqualTo(now);
	}

	@Test
	void aWatcherWhoDisconnectsAndReconnectsWithinTheGraceIsPromotedConnected() {
		build();
		twoPlaySongZero();
		lobby.join(P3, "Hana");
		lobby.disconnect(P3);
		scheduler.advance(Duration.ofSeconds(1));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		assertJoinedThenSnapshot(mark, P3, "Hana");
		skipBy(P1, P2);
		toNextPrepare();

		ready(1, P1, P2);
		assertThat(lobby.phase()).as("waits for the reconnected watcher").isEqualTo(Room.Phase.PREPARE);
		lobby.ready(P3, 1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aWatcherInGraceAtTheNextPrepareIsNotWaitedFor() {
		build();
		twoPlaySongZero();
		lobby.join(P3, "Hana");
		lobby.disconnect(P3);
		skipBy(P1, P2);
		toNextPrepare();

		ready(1, P1, P2);
		assertThat(lobby.phase()).as("the dropped watcher is not waited for").isEqualTo(Room.Phase.PLAYING);
		// Reconnecting after the promotion makes them a connected Room player.
		assertThat(lobby.reconnect(P3)).isTrue();
		answer(P3);
		assertThat(outbox.last("correct").message()).containsEntry("name", "Hana");
	}

	// ---- next game ----

	@Test
	void aPlayerWhoJoinsDuringTheLastSongIsNotRankedAndPlaysTheNextGame() {
		build();
		twoPlaySongZero();
		while (lastPrepareIndex() < 4) {
			skipBy(P1, P2);
			toNextPrepare();
			ready(lastPrepareIndex(), P1, P2);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		answer(P1);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);

		List<Sent> gameOvers = outbox.ofType("gameOver");
		assertThat(gameOvers).extracting(Sent::to).containsExactly(P1, P2, P3);
		assertThat(rankingRows(gameOvers.get(2).message())).as("only the Room players are ranked")
				.containsExactly("1:Mina:1", "2:Joon:0");

		Map<String, Object> roster = lastRosterTo(P3);
		assertThat(roster).containsEntry("phase", "lobby");
		assertThat(rosterRows(roster)).containsExactly("Mina:1:true:true", "Joon:0:false:true",
				"Hana:0:false:true");

		// The next game includes Hana from its first song.
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		ready(0, P1, P2);
		assertThat(lobby.phase()).as("Hana is a Room player").isEqualTo(Room.Phase.PREPARE);
		lobby.ready(P3, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		answer(P3);
		assertThat(scoreRows(outbox.last("scores").message().get("scores")))
				.containsExactly("Mina:0", "Joon:0", "Hana:1");
	}

	// ---- spectator reconnect ----

	@Test
	void aMemberInGraceAtStartWhoReconnectsDuringSongZeroIsARoomPlayerFromSongOne() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		lobby.start(P1, 5);
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");

		// Still a watcher during song 0: the correct title reaches only Hana and does not score.
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left));
		String title = exactTitle();
		mark = outbox.mark();
		lobby.chat(P3, title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(P3);
		assertThat(sent.get(0).message()).containsEntry("type", "chat").containsEntry("name", "Hana")
				.containsEntry("text", title);

		skipBy(P1, P2);
		toNextPrepare();
		ready(1, P1, P2);
		assertThat(lobby.phase()).as("waits for Hana's ready").isEqualTo(Room.Phase.PREPARE);
		lobby.ready(P3, 1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		answer(P3);
		assertThat(scoreRows(outbox.last("scores").message().get("scores")))
				.containsExactly("Mina:0", "Joon:0", "Hana:1");
	}

	// ---- watcher and pause ----

	@Test
	void aConnectedWatcherDoesNotPreventThePauseAndCannotResumeIt() {
		build();
		twoPlaySongZero();
		Map<String, Object> start = outbox.last("start").message();
		long left = num(start, "deadlineAt") - clock.millis() - 10000;
		assertThat(left).isPositive();
		scheduler.advance(Duration.ofMillis(left));

		lobby.join(P3, "Hana");
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		// Past the old deadline (10 s) and the following reveal pause.
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(gameSince(mark)).as("paused although the watcher is connected").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		// The watcher drops and comes back: that does not resume the game.
		lobby.disconnect(P3);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot.get("song")).as("no song while paused").isNull();
		assertThat(gameSince(mark)).as("the watcher's reconnect does not resume").isEmpty();
		scheduler.advance(Duration.ofSeconds(5));
		assertThat(gameSince(mark)).isEmpty();

		// A Room player's reconnect resumes the same song, and Hana is promoted by that prepare.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(types(gameSince(mark))).containsExactly("prepare", "prepare");
		assertThat(lastPrepareIndex()).isEqualTo(0);
		lobby.ready(P1, 0);
		assertThat(lobby.phase()).as("waits for Hana's ready").isEqualTo(Room.Phase.PREPARE);
		lobby.ready(P3, 0);
		assertThat(lobby.phase()).as("Joon is in grace and not waited for").isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aConnectedWatcherDoesNotKeepAPausedGameFromBeingAbandoned() {
		build();
		twoPlaySongZero();
		lobby.join(P3, "Hana");
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		scheduler.advance(Lobby.GRACE);
		assertThat(lobby.phase()).as("abandoned: no Room player connected or in grace")
				.isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.names()).containsExactly("Hana");
		assertThat(outbox.ofType("gameOver")).isEmpty();
	}

}
