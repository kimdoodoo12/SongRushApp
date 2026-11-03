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

// Cycle 044 (B-005c) criterion 2: the Lobby pauses its Room when no Room player is connected and
// resumes it on a Room player's reconnect, on the cycle 036 fakes (fake clock, manual scheduler,
// seeded Random, recording Outbox, real AudioTokens). As in LobbyTest, every message must reach the
// outbox as a toPlayer (docs/cycles/044-pause-empty.md).
class LobbyPauseTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	// Message types that show the game moving on.
	private static final List<String> GAME_TYPES = List.of("start", "reveal", "scores", "prepare", "gameOver");

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
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(44), outbox,
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

	// "name:score:host:connected" per player of a roster.
	@SuppressWarnings("unchecked")
	private static List<String> rosterRows(Map<String, Object> roster) {
		assertThat(roster.get("type")).isEqualTo("roster");
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) roster.get("players")) {
			Map<String, Object> p = (Map<String, Object>) o;
			rows.add(p.get("name") + ":" + num(p, "score") + ":" + p.get("host") + ":" + p.get("connected"));
		}
		return rows;
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

	private void ready(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
	}

	// The two Room players start a 5-song game and reach PLAYING of song 0.
	private Map<String, Object> joinTwoAndPlay() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		return outbox.last("start").message();
	}

	private void advanceToTenSecondsBeforeTheDeadline(Map<String, Object> start) {
		long left = num(start, "deadlineAt") - clock.millis() - 10000;
		assertThat(left).as("time left before deadline - 10 s").isPositive();
		scheduler.advance(Duration.ofMillis(left));
	}

	// From PLAYING: waits for startAt and lets the winner answer with the exact title.
	private void answer(String winner) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		lobby.chat(winner, currentAnime().titles().get("en").get(0));
		assertThat(types(outbox.since(mark))).as("answered").contains("correct");
	}

	// Plays every song up to (not including) the final reveal pause; every listed id readies.
	private void playToLastReveal(String winner, String... ids) {
		while (true) {
			if (lobby.phase() == Room.Phase.REVEAL) {
				scheduler.advance(Room.REVEAL_PAUSE);
				continue;
			}
			if (lobby.phase() == Room.Phase.PREPARE) {
				ready((int) num(outbox.last("prepare").message(), "index"), ids);
			}
			answer(winner);
			Map<String, Object> prepare = outbox.last("prepare").message();
			if (num(prepare, "index") == num(prepare, "total") - 1) {
				assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
				return;
			}
		}
	}

	// The snapshot (the second message to the player after the mark), after checking joined came first.
	private Map<String, Object> snapshotAfterJoined(List<Sent> toPlayer, String name) {
		assertThat(toPlayer.size()).isGreaterThanOrEqualTo(2);
		assertThat(toPlayer.get(0).type()).isEqualTo("joined");
		assertThat(toPlayer.get(0).message()).containsEntry("code", CODE).containsEntry("name", name);
		Map<String, Object> snapshot = toPlayer.get(1).message();
		assertThat(snapshot.get("type")).isEqualTo("snapshot");
		return snapshot;
	}

	// ---- pause and resume ----

	@Test
	void bothPlayersDisconnectingPausesTheGameAndTheFirstReconnectReprepareTheSameSong() {
		build();
		Map<String, Object> start = joinTwoAndPlay();
		Map<String, Object> firstPrepare = outbox.last("prepare").message();
		Theme theme = currentTheme();
		advanceToTenSecondsBeforeTheDeadline(start);

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		// Past the old deadline (10 s) and the following reveal pause (15 s).
		scheduler.advance(Lobby.GRACE.minusMillis(1));
		assertThat(gameSince(mark)).as("no start, reveal, scores, prepare or gameOver").isEmpty();
		assertThat(lobby.phase()).as("the phase is unchanged").isEqualTo(Room.Phase.PLAYING);
		assertThat(lobby.closed()).isFalse();

		assertThat(lobby.reconnect(P1)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).as("only P1 is connected").containsOnly(P1);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "prepare");
		Map<String, Object> snapshot = snapshotAfterJoined(sent, "Mina");
		assertThat(snapshot).containsEntry("phase", "playing").containsKey("song");
		assertThat(snapshot.get("song")).as("the paused room has no current song").isNull();
		assertThat(rosterRows(sent.get(2).message())).containsExactly("Mina:0:true:true", "Joon:0:false:false");
		Map<String, Object> prepare = sent.get(3).message();
		assertThat(num(prepare, "index")).as("the same index").isZero();
		assertThat(num(prepare, "total")).isEqualTo(5);
		assertThat(prepare.get("audioUrl")).as("a new audio token").isNotEqualTo(firstPrepare.get("audioUrl"));
		assertThat(currentTheme().id()).as("the same song").isEqualTo(theme.id());
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		// The other player, still in grace, comes back to the running game: no second prepare.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(types(toSince(mark, P2))).containsExactly("joined", "snapshot", "roster");
		@SuppressWarnings("unchecked")
		Map<String, Object> song = (Map<String, Object>) snapshotAfterJoined(toSince(mark, P2), "Joon").get("song");
		assertThat(song).isNotNull();
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(types(toSince(mark, P1))).containsExactly("roster");

		// The resumed song plays.
		mark = outbox.mark();
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(types(toSince(mark, P1))).containsExactly("start");
		assertThat(types(toSince(mark, P2))).containsExactly("start");
	}

	// ---- spectators ----

	@Test
	void aConnectedSpectatorDoesNotKeepTheGameRunningAndOnlyARoomPlayerResumesIt() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		// Hana was in grace at the start: she spectates.
		assertThat(lobby.reconnect(P3)).isTrue();
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		Map<String, Object> firstPrepare = outbox.last("prepare").message();
		advanceToTenSecondsBeforeTheDeadline(outbox.last("start").message());

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(gameSince(mark)).as("the spectator gets no reveal or prepare").isEmpty();
		assertThat(lobby.phase()).as("paused although the spectator is connected").isEqualTo(Room.Phase.PLAYING);

		// The spectator's own reconnect does not resume.
		lobby.disconnect(P3);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		List<Sent> toHana = toSince(mark, P3);
		assertThat(types(toHana)).containsExactly("joined", "snapshot", "roster");
		assertThat(snapshotAfterJoined(toHana, "Hana").get("song")).isNull();
		assertThat(gameSince(mark)).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		// A Room player's reconnect resumes, and everyone connected gets the roster, then the prepare.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(types(toSince(mark, P2))).containsExactly("joined", "snapshot", "roster", "prepare");
		assertThat(snapshotAfterJoined(toSince(mark, P2), "Joon").get("song")).isNull();
		assertThat(types(toSince(mark, P3))).containsExactly("roster", "prepare");
		assertThat(toSince(mark, P1)).isEmpty();
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(num(prepare, "index")).isZero();
		assertThat(prepare.get("audioUrl")).isNotEqualTo(firstPrepare.get("audioUrl"));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- last song ----

	@Test
	void pausedInTheLastRevealThenAReconnectEndsTheGameAndReturnsToTheLobby() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.start(P1, 5);
		playToLastReveal(P1, P1, P2, P3);

		// Inside the 5 s reveal pause: Hana drops first, then the other two.
		scheduler.advance(Duration.ofSeconds(1));
		lobby.disconnect(P3);
		scheduler.advance(Duration.ofSeconds(1));
		lobby.disconnect(P2);
		lobby.disconnect(P1);
		int mark = outbox.mark();
		// Hana's grace runs out; Mina's and Joon's do not.
		scheduler.advance(Lobby.GRACE.minusMillis(500));
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");

		assertThat(lobby.reconnect(P1)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsOnly(P1);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "gameOver", "roster");
		Map<String, Object> snapshot = snapshotAfterJoined(sent, "Mina");
		assertThat(snapshot).containsEntry("phase", "playing");
		assertThat(snapshot.get("song")).isNull();
		assertThat(sent.get(2).message()).containsEntry("phase", "playing");
		Map<String, Object> after = sent.get(4).message();
		assertThat(after).containsEntry("phase", "lobby");
		assertThat(rosterRows(after)).as("Joon, still in grace, stays; Hana is removed")
				.containsExactly("Mina:5:true:true", "Joon:0:false:false");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.names()).containsExactly("Mina", "Joon");
		assertThat(lobby.reconnect(P3)).isFalse();
		assertThat(lobby.reconnect(P2)).isTrue();
	}

	// ---- disconnect then leave ----

	@Test
	void oneDisconnectsAndTheOtherLeavesPausesTheGame() {
		build();
		Map<String, Object> start = joinTwoAndPlay();
		Map<String, Object> firstPrepare = outbox.last("prepare").message();
		advanceToTenSecondsBeforeTheDeadline(start);

		lobby.disconnect(P1);
		lobby.leave(P2);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(gameSince(mark)).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(types(toSince(mark, P1))).containsExactly("joined", "snapshot", "roster", "prepare");
		assertThat(toSince(mark, P2)).as("a member who left gets nothing").isEmpty();
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(num(prepare, "index")).isZero();
		assertThat(prepare.get("audioUrl")).isNotEqualTo(firstPrepare.get("audioUrl"));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- no resume ----

	@Test
	void ifNobodyReconnectsNothingIsSentAndTheLobbyClosesAtTheEmptyTtl() {
		build();
		joinTwoAndPlay();
		scheduler.advance(Duration.ofSeconds(4));
		lobby.disconnect(P1);
		scheduler.advance(Duration.ofSeconds(5));
		lobby.disconnect(P2);
		int mark = outbox.mark();

		scheduler.advance(Lobby.EMPTY_TTL.minusMillis(1));
		assertThat(outbox.since(mark)).as("nothing is sent").isEmpty();
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();
		assertThat(lobby.phase()).as("abandoned once the last grace expired (cycle 044 attempt 2)").isEqualTo(Room.Phase.LOBBY);

		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(lobby.reconnect(P2)).isFalse();
	}

	// ---- one player left ----

	@Test
	void withOnePlayerStillConnectedADisconnectDoesNotPause() {
		build();
		Map<String, Object> start = joinTwoAndPlay();
		advanceToTenSecondsBeforeTheDeadline(start);

		lobby.disconnect(P1);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(10));
		assertThat(types(toSince(mark, P2))).containsExactly("reveal", "scores");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);

		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(toSince(mark, P2))).containsExactly("prepare");
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

}
