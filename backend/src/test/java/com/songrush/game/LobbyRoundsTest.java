package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
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

// Cycle 045 (B-006b) criterion 2: the Lobby passes connection changes, reconnects and skips to its
// Room, on the cycle 036 fakes (fake clock, manual scheduler, seeded Random, recording Outbox, real
// AudioTokens). As in LobbyTest, every message must reach the outbox as a toPlayer
// (docs/cycles/045-multiplayer-rounds.md).
class LobbyRoundsTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

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
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(45), outbox,
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

	// "index:votes/needed" of every skipState sent to the player since the mark.
	private List<String> skipRowsTo(int mark, String playerId) {
		return toSince(mark, playerId).stream().filter(s -> "skipState".equals(s.type())).map(s -> {
			assertThat(s.message().keySet()).containsExactlyInAnyOrder("type", "index", "votes", "needed");
			return num(s.message(), "index") + ":" + num(s.message(), "votes") + "/" + num(s.message(), "needed");
		}).toList();
	}

	private boolean startedSince(int mark, int index) {
		return outbox.since(mark).stream()
				.anyMatch(s -> "start".equals(s.type()) && num(s.message(), "index") == index);
	}

	private void ready(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
	}

	private void joinThree() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
	}

	// Three Room players reach PLAYING of song 0, past startAt.
	private void threePlaying() {
		joinThree();
		lobby.start(P1, 5);
		ready(0, P1, P2, P3);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
	}

	private void assertRevealWithNoWinnerTo(int mark, String playerId) {
		List<Sent> reveals = toSince(mark, playerId).stream().filter(s -> "reveal".equals(s.type())).toList();
		assertThat(reveals).as("reveal to " + playerId).hasSize(1);
		assertThat(reveals.get(0).message()).containsKey("winner");
		assertThat(reveals.get(0).message().get("winner")).isNull();
	}

	// ---- tests ----

	@Test
	void afterOneOfThreeDisconnectsTheOtherTwoStartTheGameOnTheirReady() {
		build();
		joinThree();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		lobby.disconnect(P3);

		long now = clock.millis();
		int mark = outbox.mark();
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(startedSince(mark, 0)).isTrue();
		assertThat(num(outbox.last("start").message(), "serverTime")).isEqualTo(now);
		assertThat(toSince(mark, P3)).as("nothing reaches the disconnected player").isEmpty();
	}

	@Test
	void aSkipThroughTheLobbyEndsTheSong() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		lobby.start(P1, 5);
		ready(0, P1, P2);
		scheduler.advance(Room.LEAD);

		int mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P1)).containsExactly("0:1/2");
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		mark = outbox.mark();
		lobby.skip(P2, 0);
		for (String id : List.of(P1, P2)) {
			assertThat(types(toSince(mark, id))).as("to " + id).containsExactly("skipState", "reveal", "scores");
			assertThat(skipRowsTo(mark, id)).containsExactly("0:2/2");
			assertRevealWithNoWinnerTo(mark, id);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void aSkipFromADisconnectedMemberIsNotForwarded() {
		build();
		threePlaying();
		lobby.disconnect(P3);

		int mark = outbox.mark();
		lobby.skip(P3, 0);
		assertThat(outbox.since(mark)).isEmpty();

		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P1)).containsExactly("0:1/2");
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");
		assertThat(toSince(mark, P3)).isEmpty();

		mark = outbox.mark();
		lobby.skip(P2, 0);
		assertThat(skipRowsTo(mark, P1)).containsExactly("0:2/2");
		assertRevealWithNoWinnerTo(mark, P1);
		assertRevealWithNoWinnerTo(mark, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void aReconnectedRoomPlayerIsCountedInTheReadyRuleOfTheNextSong() {
		build();
		threePlaying();
		lobby.disconnect(P3);
		assertThat(lobby.reconnect(P3)).isTrue();

		long left = num(outbox.last("start").message(), "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(left));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(1);

		int mark = outbox.mark();
		ready(1, P1, P2);
		assertThat(lobby.phase()).as("Hana is counted again").isEqualTo(Room.Phase.PREPARE);
		assertThat(startedSince(mark, 1)).isFalse();
		lobby.ready(P3, 1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(startedSince(mark, 1)).isTrue();
	}

	@Test
	void aLeavingPlayersSkipVoteIsRemoved() {
		build();
		threePlaying();
		int mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");

		mark = outbox.mark();
		lobby.leave(P1);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:0/2");
		assertThat(skipRowsTo(mark, P3)).containsExactly("0:0/2");
		assertThat(toSince(mark, P1)).as("nothing after leave").isEmpty();

		mark = outbox.mark();
		lobby.skip(P2, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aDisconnectingPlayersSkipVoteIsRemovedAndAGraceReconnectCountsThemAgain() {
		build();
		threePlaying();
		int mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");

		mark = outbox.mark();
		lobby.disconnect(P1);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:0/2");
		assertThat(skipRowsTo(mark, P3)).containsExactly("0:0/2");

		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		List<String> toMina = types(toSince(mark, P1));
		assertThat(toMina.size()).isGreaterThanOrEqualTo(2);
		assertThat(toMina.subList(0, 2)).as("joined and snapshot come first").containsExactly("joined", "snapshot");

		mark = outbox.mark();
		lobby.skip(P2, 0);
		assertThat(skipRowsTo(mark, P1)).containsExactly("0:1/2");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:2/2");
		assertRevealWithNoWinnerTo(mark, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void aReplacementReconnectOfAVoterRemovesTheVote() {
		build();
		threePlaying();
		int mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");

		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(types(toSince(mark, P1))).containsExactly("joined", "snapshot", "skipState");
		assertThat(skipRowsTo(mark, P1)).containsExactly("0:0/2");
		assertThat(types(toSince(mark, P2))).containsExactly("skipState");
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:0/2");
		assertThat(skipRowsTo(mark, P3)).containsExactly("0:0/2");

		mark = outbox.mark();
		lobby.skip(P2, 0);
		assertThat(skipRowsTo(mark, P2)).as("Mina's vote is gone").containsExactly("0:1/2");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aReplacementReconnectWithNoVoteSendsOnlyJoinedAndSnapshot() {
		build();
		threePlaying();
		int mark = outbox.mark();
		lobby.skip(P1, 0);
		assertThat(skipRowsTo(mark, P2)).containsExactly("0:1/2");

		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("joined", "snapshot");
		assertThat(sent).allMatch(s -> P2.equals(s.to()));

		mark = outbox.mark();
		lobby.skip(P3, 0);
		assertThat(skipRowsTo(mark, P3)).as("Mina's vote is still there").containsExactly("0:2/2");
		assertRevealWithNoWinnerTo(mark, P3);
	}

}
