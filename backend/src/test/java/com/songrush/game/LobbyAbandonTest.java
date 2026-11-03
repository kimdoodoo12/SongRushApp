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
import com.songrush.catalog.Catalog;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 044 (B-005c) criterion 2b: while paused, once no Room player is connected or in grace any
// more, the Lobby abandons the game: it drops the Room, returns to LOBBY, sends roster{phase:"lobby"}
// to the connected members (spectators), arms the idle timer, and sends no gameOver. On the cycle
// 036 fakes; every message must reach the outbox as a toPlayer (docs/cycles/044-pause-empty.md).
class LobbyAbandonTest {

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

	private Lobby lobby;

	private void build() {
		Catalog catalog = GameFixture.build(dir, SEVEN, 0);
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

	// Mina and Joon play a 5-song game; Hana, in grace at the start, reconnects and spectates. Ends in
	// PLAYING of song 0, 10 s before its deadline, with all three connected.
	private void playWithSpectatorUntilTenSecondsBeforeTheDeadline() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(lobby.reconnect(P3)).isTrue();
		lobby.ready(P1, 0);
		lobby.ready(P2, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		advanceToTenSecondsBeforeTheDeadline(outbox.last("start").message());
	}

	private void advanceToTenSecondsBeforeTheDeadline(Map<String, Object> start) {
		long left = num(start, "deadlineAt") - clock.millis() - 10000;
		assertThat(left).as("time left before deadline - 10 s").isPositive();
		scheduler.advance(Duration.ofMillis(left));
	}

	// After an abandon seen from the mark: Hana got exactly one roster{phase:"lobby"} listing only
	// herself as host, nobody got a game message, and the lobby is back in LOBBY.
	private void assertAbandonedWithOnlyHana(int mark) {
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).as("only Hana is connected").containsOnly(P3);
		assertThat(types(sent)).as("only the lobby roster, no gameOver").containsExactly("roster");
		Map<String, Object> roster = sent.get(0).message();
		assertThat(roster).containsEntry("phase", "lobby");
		assertThat(rosterRows(roster)).as("Mina and Joon are removed").containsExactly("Hana:0:true:true");
		assertThat(gameSince(mark)).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.names()).containsExactly("Hana");
		assertThat(lobby.host()).isEqualTo(P3);
		assertThat(lobby.closed()).isFalse();
		assertThat(lobby.reconnect(P1)).as("Mina's grace has expired").isFalse();
		assertThat(lobby.reconnect(P2)).as("Joon's grace has expired").isFalse();
	}

	// ---- abandon: both Room players' grace expires ----

	@Test
	void whenBothRoomPlayersGraceExpiresTheSpectatorIsBackInTheLobbyAndCanStartAgain() {
		build();
		playWithSpectatorUntilTenSecondsBeforeTheDeadline();

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		scheduler.advance(Lobby.GRACE.minusMillis(1));
		assertThat(outbox.since(mark)).as("still paused: nothing is sent").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		scheduler.advance(Duration.ofMillis(1));
		assertAbandonedWithOnlyHana(mark);

		// Hana, now the host, starts a new game on her own.
		mark = outbox.mark();
		lobby.start(P3, 5);
		assertThat(toSince(mark, P3)).extracting(Sent::type).as("no error").doesNotContain("error");
		assertThat(toSince(mark, P3)).extracting(Sent::type).contains("prepare");
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(num(prepare, "index")).isZero();
		assertThat(num(prepare, "total")).isEqualTo(5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		mark = outbox.mark();
		lobby.ready(P3, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(types(toSince(mark, P3))).containsExactly("start");
	}

	@Test
	void afterTheAbandonALobbyNobodyActsInClosesAtTheIdleTtl() {
		build();
		playWithSpectatorUntilTenSecondsBeforeTheDeadline();

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		scheduler.advance(Lobby.GRACE);
		assertAbandonedWithOnlyHana(mark);

		mark = outbox.mark();
		scheduler.advance(Lobby.IDLE_TTL.minusMillis(1));
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();

		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("error");
		assertThat(sent.get(0).to()).isEqualTo(P3);
		assertThat(sent.get(0).message()).containsEntry("code", "room-closed");
	}

	// ---- variant: one leaves while the other is in grace ----

	@Test
	void oneRoomPlayerLeavesWhileTheOtherIsInGraceThenThatGraceExpires() {
		build();
		playWithSpectatorUntilTenSecondsBeforeTheDeadline();

		lobby.disconnect(P1);
		lobby.leave(P2);
		assertThat(lobby.phase()).as("paused, not abandoned: Mina is in grace").isEqualTo(Room.Phase.PLAYING);
		int mark = outbox.mark();
		scheduler.advance(Lobby.GRACE.minusMillis(1));
		assertThat(outbox.since(mark)).as("still paused: nothing is sent").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		scheduler.advance(Duration.ofMillis(1));
		assertAbandonedWithOnlyHana(mark);
	}

	@Test
	void theLastRoomPlayerInGraceSendingLeaveAbandonsAtOnce() {
		build();
		playWithSpectatorUntilTenSecondsBeforeTheDeadline();

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		scheduler.advance(Duration.ofSeconds(5));
		int mark = outbox.mark();
		lobby.leave(P2);
		assertThat(outbox.since(mark)).as("Mina is still in grace: nothing is sent").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		lobby.leave(P1);
		assertAbandonedWithOnlyHana(mark);
	}

	// ---- the dropped Room stays silent ----

	@Test
	void theDroppedRoomSendsNothingAfterTheAbandon() {
		build();
		playWithSpectatorUntilTenSecondsBeforeTheDeadline();

		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		scheduler.advance(Lobby.GRACE);
		assertAbandonedWithOnlyHana(mark);

		// Well past the old deadline, several reveal pauses and prepare timeouts; still before IDLE_TTL.
		mark = outbox.mark();
		scheduler.advance(Duration.ofMinutes(5));
		lobby.chat(P3, "hello");
		lobby.ready(P3, 0);
		lobby.ready(P3, 1);
		scheduler.advance(Duration.ofMinutes(1));
		assertThat(outbox.since(mark)).as("nothing from the dropped Room").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.closed()).isFalse();
	}

	// ---- without a spectator: the empty timer still closes the lobby ----

	@Test
	void withoutASpectatorTheLobbyStillClosesAtTheEmptyTtlAfterTheLastDisconnect() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		lobby.start(P1, 5);
		lobby.ready(P1, 0);
		lobby.ready(P2, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Duration.ofSeconds(4));
		lobby.disconnect(P1);
		scheduler.advance(Duration.ofSeconds(5));
		lobby.disconnect(P2);
		int mark = outbox.mark();

		// Both graces have run out: the game is abandoned, with nobody to tell.
		scheduler.advance(Lobby.GRACE.plusMillis(1));
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.closed()).isFalse();
		assertThat(lobby.reconnect(P1)).isFalse();
		assertThat(lobby.reconnect(P2)).isFalse();

		// The empty timer still runs from the last disconnect; the abandon does not push it back.
		scheduler.advance(Lobby.EMPTY_TTL.minus(Lobby.GRACE).minusMillis(2));
		assertThat(outbox.since(mark)).as("nothing is sent").isEmpty();
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();

		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		assertThat(outbox.since(mark)).isEmpty();
	}

}
