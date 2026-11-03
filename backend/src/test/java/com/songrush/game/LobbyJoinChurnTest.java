package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 046 criterion 2b: during a game the lobby holds at most 2 * MAX_PLAYERS (16) members of any
// state, so one socket repeating join and leave cannot grow the member list, the Room's scores or
// the ranking without bound. In LOBBY the 8-member cap is unchanged. Runs on the cycle 036 fakes;
// as in LobbyTest, every message must reach the outbox as a toPlayer
// (docs/cycles/046-late-joiners.md, "Code review round 1 and attempt 2").
class LobbyJoinChurnTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String STAY = "pid-stay";

	private static final int GAME_CAP = 2 * Lobby.MAX_PLAYERS;

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Lobby lobby;

	private int churnId;

	private void build() {
		Catalog catalog = GameFixture.build(dir, SEVEN, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(46), outbox, () -> {
		});
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

	private int lastPrepareIndex() {
		return (int) num(outbox.last("prepare").message(), "index");
	}

	private static int rows(Object list) {
		assertThat(list).isInstanceOf(List.class);
		return ((List<?>) list).size();
	}

	// Mina, Joon and Hana start a 5-song game and reach PLAYING of song 0.
	private void threePlaySongZero() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		readyAll(0, P1, P2, P3);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	private void readyAll(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
	}

	// One join + leave by a new id. Below the cap the join succeeds; at the cap it gets room-full,
	// and that error is the only message sent. Returns true when the join was refused.
	private boolean churnOnce() {
		String id = "churn-" + churnId;
		String nick = "C" + churnId;
		churnId++;
		int before = lobby.names().size();
		int mark = outbox.mark();
		String name = lobby.join(id, nick);
		if (before >= GAME_CAP) {
			assertThat(name).as("join #" + churnId + " at " + before + " members").isNull();
			List<Sent> sent = outbox.since(mark);
			assertThat(sent).as("only the room-full error to the joiner").hasSize(1);
			assertThat(sent.get(0).to()).isEqualTo(id);
			assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "room-full");
			lobby.leave(id);
			assertThat(outbox.since(mark)).as("a refused joiner's leave sends nothing").hasSize(1);
		}
		else {
			assertThat(name).as("join #" + churnId + " at " + before + " members").isEqualTo(nick);
			lobby.leave(id);
		}
		assertThat(lobby.names().size()).as("members after churn #" + churnId).isLessThanOrEqualTo(GAME_CAP);
		return name == null;
	}

	private int churn(int times) {
		int refused = 0;
		for (int i = 0; i < times; i++) {
			if (churnOnce()) {
				refused++;
			}
		}
		return refused;
	}

	// From PLAYING: the listed connected Room players skip, the reveal pause ends and the next song
	// starts after their ready.
	private void crossSongBoundary(String... ids) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		int index = lastPrepareIndex();
		for (String id : ids) {
			lobby.skip(id, index);
		}
		assertThat(lobby.phase()).as("the skip passed").isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(lastPrepareIndex()).isEqualTo(index + 1);
		readyAll(index + 1, ids);
		assertThat(lobby.phase()).as("the departed churners are not waited for").isEqualTo(Room.Phase.PLAYING);
	}

	// Every scores, snapshot, roster and gameOver message so far carries at most 16 rows.
	private void assertNoMessageOverCap() {
		for (Sent s : outbox.all()) {
			switch (s.type()) {
				case "scores", "snapshot" -> assertThat(rows(s.message().get("scores")))
						.as(s.type() + " rows to " + s.to()).isLessThanOrEqualTo(GAME_CAP);
				case "roster" -> assertThat(rows(s.message().get("players")))
						.as("roster rows to " + s.to()).isLessThanOrEqualTo(GAME_CAP);
				case "gameOver" -> assertThat(rows(s.message().get("ranking")))
						.as("ranking rows to " + s.to()).isLessThanOrEqualTo(GAME_CAP);
				default -> {
				}
			}
		}
	}

	// ---- the cap during a game ----

	@Test
	void aHundredJoinLeaveRoundsKeepTheMembersAtSixteen() {
		build();
		threePlaySongZero();
		int refused = churn(100);
		assertThat(lobby.names()).as("3 players + 13 departed churners").hasSize(GAME_CAP);
		assertThat(refused).as("joins beyond the 16th member are refused").isEqualTo(100 - (GAME_CAP - 3));
		assertThat(lobby.names()).startsWith("Mina", "Joon", "Hana");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		assertNoMessageOverCap();
	}

	@Test
	void theJoinAfterSixteenMembersGetsRoomFullAndOnlyTheJoinerHearsIt() {
		build();
		threePlaySongZero();
		assertThat(churn(GAME_CAP - 3)).as("the first 13 churners are let in").isZero();
		assertThat(lobby.names()).hasSize(GAME_CAP);

		// Only 3 seats are taken, but the member list is full.
		int mark = outbox.mark();
		assertThat(lobby.join("pid-late", "Late")).isNull();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).as("only the error to the joiner").hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo("pid-late");
		assertThat(sent.get(0).message().keySet()).containsExactlyInAnyOrder("type", "code");
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "room-full");
		assertThat(lobby.names()).hasSize(GAME_CAP).doesNotContain("Late");

		// The nickname is still checked first.
		assertThat(lobby.join("pid-late", "")).isNull();
		assertThat(outbox.since(mark).get(outbox.since(mark).size() - 1).message())
				.containsEntry("code", "bad-nickname");
	}

	@Test
	void churnAcrossSongBoundariesKeepsTheRoomScoresAtSixteenRows() {
		build();
		threePlaySongZero();
		churn(10);
		crossSongBoundary(P1, P2, P3);
		churn(20);
		crossSongBoundary(P1, P2, P3);
		churn(20);
		crossSongBoundary(P1, P2, P3);
		churn(50);
		crossSongBoundary(P1, P2, P3);
		assertThat(lastPrepareIndex()).isEqualTo(4);
		assertThat(lobby.names()).hasSize(GAME_CAP);

		List<Sent> scores = outbox.ofType("scores");
		assertThat(scores).as("scores were sent at each boundary").isNotEmpty();
		int maxRows = 0;
		for (Sent s : scores) {
			maxRows = Math.max(maxRows, rows(s.message().get("scores")));
		}
		assertThat(maxRows).as("the Room's scores rows").isLessThanOrEqualTo(GAME_CAP);
		assertNoMessageOverCap();
	}

	@Test
	void afterGameOverTheLobbyHoldsOnlyTheStillConnectedMembers() {
		build();
		threePlaySongZero();
		assertThat(lobby.join(STAY, "Stay")).isEqualTo("Stay");
		churn(10);
		// Stay is a watcher during song 0 (the skip is ignored) and a Room player from song 1.
		crossSongBoundary(P1, P2, P3, STAY);
		churn(30);
		crossSongBoundary(P1, P2, P3, STAY);
		churn(30);
		crossSongBoundary(P1, P2, P3, STAY);
		churn(30);
		crossSongBoundary(P1, P2, P3, STAY);
		assertThat(lastPrepareIndex()).isEqualTo(4);
		assertThat(lobby.names()).hasSize(GAME_CAP);

		// The last song ends: gameOver, then the return.
		for (String id : List.of(P1, P2, P3, STAY)) {
			lobby.skip(id, 4);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.ofType("gameOver")).extracting(Sent::to).containsExactly(P1, P2, P3, STAY);
		assertThat(lobby.names()).as("only the still-connected members return")
				.containsExactly("Mina", "Joon", "Hana", "Stay");
		assertNoMessageOverCap();

		// Back in LOBBY the 8-member cap applies again.
		for (int i = 0; i < Lobby.MAX_PLAYERS - 4; i++) {
			assertThat(lobby.join("pid-next-" + i, "N" + i)).isEqualTo("N" + i);
		}
		int mark = outbox.mark();
		assertThat(lobby.join("pid-ninth", "Ninth")).isNull();
		assertThat(outbox.since(mark)).hasSize(1);
		assertThat(outbox.since(mark).get(0).message()).containsEntry("code", "room-full");
	}

	// ---- LOBBY is unchanged ----

	@Test
	void inLobbyTheEightMemberCapIsUnchangedAndChurnIsNotCapped() {
		build();
		for (int i = 1; i <= Lobby.MAX_PLAYERS; i++) {
			assertThat(lobby.join("pid-" + i, "Name" + i)).isEqualTo("Name" + i);
		}
		int mark = outbox.mark();
		assertThat(lobby.join("pid-9", "Ivy")).isNull();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo("pid-9");
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "room-full");

		// In LOBBY a leaver is removed, so the seat frees at once.
		lobby.leave("pid-8");
		assertThat(lobby.names()).hasSize(Lobby.MAX_PLAYERS - 1);
		assertThat(lobby.join("pid-9", "Ivy")).isEqualTo("Ivy");
		lobby.leave("pid-9");

		// A hundred join + leave rounds in LOBBY all succeed and leave 7 members.
		List<String> names = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			String nick = "L" + i;
			names.add(lobby.join("pid-lobby-" + i, nick));
			lobby.leave("pid-lobby-" + i);
			assertThat(lobby.names()).hasSize(Lobby.MAX_PLAYERS - 1);
		}
		assertThat(names).doesNotContainNull();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
	}

}
