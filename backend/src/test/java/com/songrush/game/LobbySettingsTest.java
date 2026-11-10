package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 116 (D-124, critic H1 of 114) acceptance 1: Lobby.setLobbySettings(playerId, songCount,
// filter, answerMode, gameMode) on the cycle 036 fakes (fake clock, manual scheduler, seeded Random,
// recording Outbox, real AudioTokens). The host's lobby selection is stored and sent to every
// connected member as {type:"lobbySettings", songCount, ...the filter's set fields (types, yearFrom,
// yearTo, tier), answerMode only for "choice", gameMode only for "ranked"}: the fields of the start
// message, defaults left out as in settings. The lobby-side checks are start's, in start's order:
// in-game, not-host, bad-song-count. A selection equal to the stored one is not broadcast. A
// joiner, a grace rejoin and a replaced socket in the lobby get the stored selection right after
// roomTheme (or where roomTheme would be); nobody gets it during a game. It survives a game and a
// host transfer. As in LobbyTest, every message must reach the outbox as a toPlayer
// (docs/cycles/116-lobby-settings.md).
class LobbySettingsTest {

	private static final String CODE = "SETTS";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String P4 = "pid-4";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	// OP only, 2000-2010, easy.
	private static final DrawFilter FILTER = new DrawFilter(Set.of("OP"), 2000, 2010, DrawFilter.EASY);

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
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(116), outbox,
				closedCalls::incrementAndGet);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	// {type:"lobbySettings", songCount} plus the given keys and values.
	private static Map<String, Object> lobbySettings(int songCount, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "lobbySettings");
		m.put("songCount", songCount);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	// The wire form of (12, FILTER, CHOICE, RANKED).
	private static Map<String, Object> full() {
		return lobbySettings(12, "types", List.of("OP"), "yearFrom", 2000, "yearTo", 2010, "tier", "easy",
				"answerMode", "choice", "gameMode", "ranked");
	}

	private static Map<String, Object> error(String code) {
		return Map.of("type", "error", "code", code);
	}

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private List<Sent> toSince(int mark, String playerId) {
		return outbox.since(mark).stream().filter(s -> playerId.equals(s.to())).toList();
	}

	private List<Sent> lobbySettingsSince(int mark) {
		return outbox.since(mark).stream().filter(s -> "lobbySettings".equals(s.type())).toList();
	}

	private void joinTwo() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.host()).isEqualTo(P1);
	}

	private void joinThree() {
		joinTwo();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
	}

	// The host stores (12, FILTER, CHOICE, RANKED); every id in connected gets exactly full().
	private void setFull(String host, List<String> connected) {
		int mark = outbox.mark();
		lobby.setLobbySettings(host, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		for (String id : connected) {
			assertThat(toSince(mark, id)).as("to " + id).extracting(Sent::message).containsExactly(full());
		}
		assertThat(outbox.since(mark)).hasSize(connected.size());
	}

	// The sender gets exactly the error, and nobody else gets anything.
	private void rejected(String playerId, int songCount, DrawFilter filter, AnswerMode mode, GameMode gameMode,
			String code) {
		int mark = outbox.mark();
		lobby.setLobbySettings(playerId, songCount, filter, mode, gameMode);
		assertThat(outbox.since(mark)).as(code).extracting(Sent::to).containsExactly(playerId);
		assertThat(outbox.since(mark)).as(code).extracting(Sent::message).containsExactly(error(code));
	}

	private void start() {
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// Ends the current song with both Room players' ready and skip vote; the reveal pause then runs.
	private void skipSong(int index) {
		for (String id : List.of(P1, P2)) {
			lobby.ready(id, index);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		for (String id : List.of(P1, P2)) {
			lobby.skip(id, index);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
	}

	private void playToTheEnd() {
		for (int k = 0; k < 5; k++) {
			skipSong(k);
		}
		assertThat(lobby.phase()).as("after gameOver").isEqualTo(Room.Phase.LOBBY);
	}

	// ---- the broadcast ----

	@Test
	void theHostsSelectionReachesEveryConnectedMemberAndNoDisconnectedOne() {
		build();
		joinThree();
		lobby.disconnect(P3);
		int mark = outbox.mark();
		lobby.setLobbySettings(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		for (String id : List.of(P1, P2)) {
			assertThat(toSince(mark, id)).as("to " + id).extracting(Sent::message).containsExactly(full());
		}
		assertThat(toSince(mark, P3)).as("nothing to the disconnected member").isEmpty();
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P1, P2);
	}

	@Test
	void defaultFieldsAreLeftOutAsInStart() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.setLobbySettings(P1, 10, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		assertThat(lobbySettingsSince(mark)).extracting(Sent::message)
				.containsExactly(lobbySettings(10), lobbySettings(10));

		mark = outbox.mark();
		lobby.setLobbySettings(P1, 7, new DrawFilter(Set.of("OP", "ED"), null, 2004, DrawFilter.HARD),
				AnswerMode.CHAT, GameMode.RANKED);
		Map<String, Object> expected = lobbySettings(7, "types", List.of("OP", "ED"), "yearTo", 2004, "tier", "hard",
				"gameMode", "ranked");
		assertThat(lobbySettingsSince(mark)).extracting(Sent::message).containsExactly(expected, expected);
	}

	@Test
	void theSongCountBoundsFiveAndThirtyAreAccepted() {
		build();
		joinTwo();
		for (int count : new int[] {5, 30}) {
			int mark = outbox.mark();
			lobby.setLobbySettings(P1, count, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
			assertThat(lobbySettingsSince(mark)).as("count " + count).extracting(Sent::message)
					.containsExactly(lobbySettings(count), lobbySettings(count));
		}
	}

	// ---- errors, in start's order ----

	@Test
	void aGuestGetsNotHostAndNothingIsStored() {
		build();
		joinTwo();
		rejected(P2, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "not-host");

		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).as("no selection was stored").containsExactly("joined", "roster");
	}

	@Test
	void aGuestsBadSongCountIsNotHostBeforeBadSongCount() {
		build();
		joinTwo();
		for (int bad : new int[] {4, 31, 0, -1}) {
			rejected(P2, bad, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST, "not-host");
		}
	}

	@Test
	void theHostsSongCountOutsideFiveToThirtyIsBadSongCountAndTheStoredSelectionStays() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		for (int bad : new int[] {4, 31, 0, -1, Integer.MAX_VALUE}) {
			rejected(P1, bad, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "bad-song-count");
		}
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "lobbySettings");
		assertThat(sent.get(2).message()).isEqualTo(full());
	}

	@Test
	void duringAGameEveryoneGetsInGameFirst() {
		build();
		joinTwo();
		start();
		// in-game before not-host and before bad-song-count, for the host and a guest alike.
		rejected(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "in-game");
		rejected(P1, 99, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "in-game");
		rejected(P2, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "in-game");
		rejected(P2, 99, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST, "in-game");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void aSelectionRefusedDuringAGameIsNotStored() {
		build();
		joinTwo();
		start();
		rejected(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED, "in-game");
		playToTheEnd();
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "roster");
	}

	@Test
	void aDisconnectedMembersSelectionIsIgnored() {
		build();
		joinThree();
		lobby.disconnect(P2);
		int mark = outbox.mark();
		lobby.setLobbySettings(P2, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(outbox.since(mark)).isEmpty();

		lobby.disconnect(P1);
		mark = outbox.mark();
		lobby.setLobbySettings(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(outbox.since(mark)).isEmpty();
	}

	// ---- change only ----

	@Test
	void anUnchangedSelectionIsAcceptedSilently() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		int mark = outbox.mark();
		lobby.setLobbySettings(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		lobby.setLobbySettings(P1, 12, new DrawFilter(Set.of("OP"), 2000, 2010, "easy"), AnswerMode.CHOICE,
				GameMode.RANKED);
		assertThat(outbox.since(mark)).as("no broadcast and no error").isEmpty();

		// A change is broadcast, and so is the change back.
		mark = outbox.mark();
		lobby.setLobbySettings(P1, 13, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(lobbySettingsSince(mark)).hasSize(2);
		setFull(P1, List.of(P1, P2));
	}

	@Test
	void eachFieldOnItsOwnCountsAsAChange() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		Object[][] changes = {
			{12, new DrawFilter(Set.of("ED"), 2000, 2010, DrawFilter.EASY), AnswerMode.CHOICE, GameMode.RANKED},
			{12, new DrawFilter(Set.of("ED"), 2001, 2010, DrawFilter.EASY), AnswerMode.CHOICE, GameMode.RANKED},
			{12, new DrawFilter(Set.of("ED"), 2001, 2011, DrawFilter.EASY), AnswerMode.CHOICE, GameMode.RANKED},
			{12, new DrawFilter(Set.of("ED"), 2001, 2011, DrawFilter.HARD), AnswerMode.CHOICE, GameMode.RANKED},
			{12, new DrawFilter(Set.of("ED"), 2001, 2011, DrawFilter.HARD), AnswerMode.CHAT, GameMode.RANKED},
			{12, new DrawFilter(Set.of("ED"), 2001, 2011, DrawFilter.HARD), AnswerMode.CHAT, GameMode.FIRST},
			{11, new DrawFilter(Set.of("ED"), 2001, 2011, DrawFilter.HARD), AnswerMode.CHAT, GameMode.FIRST},
		};
		for (Object[] c : changes) {
			int mark = outbox.mark();
			lobby.setLobbySettings(P1, (Integer) c[0], (DrawFilter) c[1], (AnswerMode) c[2], (GameMode) c[3]);
			assertThat(lobbySettingsSince(mark)).as("change " + List.of(c)).extracting(Sent::to)
					.containsExactly(P1, P2);
		}
	}

	@Test
	void normalTierIsTheSameAsNoTier() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.setLobbySettings(P1, 10, new DrawFilter(null, null, null, DrawFilter.NORMAL), AnswerMode.CHAT,
				GameMode.FIRST);
		assertThat(lobbySettingsSince(mark)).extracting(Sent::message)
				.containsExactly(lobbySettings(10), lobbySettings(10));
		mark = outbox.mark();
		lobby.setLobbySettings(P1, 10, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		assertThat(outbox.since(mark)).isEmpty();
	}

	// ---- who gets the stored selection ----

	@Test
	void beforeAnySelectionNoJoinPathSendsLobbySettings() {
		build();
		int start = outbox.mark();
		joinThree();
		lobby.disconnect(P3);
		assertThat(lobby.reconnect(P3)).isTrue();
		assertThat(lobby.reconnect(P3)).isTrue();
		assertThat(lobbySettingsSince(start)).isEmpty();
	}

	@Test
	void aLobbyJoinerGetsJoinedRosterThenLobbySettingsAndOnlyTheJoiner() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "lobbySettings");
		assertThat(sent.get(2).message()).isEqualTo(full());
		assertThat(lobbySettingsSince(mark)).hasSize(1);
		for (String id : List.of(P1, P2)) {
			assertThat(types(toSince(mark, id))).as("to " + id).containsExactly("roster");
		}
	}

	@Test
	void aLobbyJoinerGetsLobbySettingsRightAfterRoomTheme() {
		build();
		joinTwo();
		lobby.setRoomTheme(P1, "neon");
		setFull(P1, List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "roomTheme", "lobbySettings");
		assertThat(sent.get(3).message()).isEqualTo(full());
	}

	@Test
	void aGraceRejoinInTheLobbyGetsLobbySettingsAfterRosterAndRoomTheme() {
		build();
		joinThree();
		setFull(P1, List.of(P1, P2, P3));
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "lobbySettings");
		assertThat(sent.get(3).message()).isEqualTo(full());
		assertThat(lobbySettingsSince(mark)).hasSize(1);

		lobby.setRoomTheme(P1, "rain");
		lobby.disconnect(P3);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme", "lobbySettings");
		assertThat(sent.get(4).message()).isEqualTo(full());
	}

	@Test
	void aReplacedSocketInTheLobbyGetsJoinedSnapshotThenLobbySettings() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P2, P2, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "lobbySettings");
		assertThat(sent.get(2).message()).isEqualTo(full());

		lobby.setRoomTheme(P1, "pixel");
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roomTheme", "lobbySettings");
		assertThat(sent.get(3).message()).isEqualTo(full());
	}

	@Test
	void duringAGameNoJoinPathSendsLobbySettings() {
		build();
		joinThree();
		setFull(P1, List.of(P1, P2, P3));
		lobby.disconnect(P3);
		start();
		int mark = outbox.mark();

		// A late joiner.
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		assertThat(types(toSince(mark, P4))).containsExactly("joined", "snapshot", "roster");
		// A grace rejoin of a member who was in grace at start, and of a Room player.
		assertThat(lobby.reconnect(P3)).isTrue();
		lobby.disconnect(P2);
		assertThat(lobby.reconnect(P2)).isTrue();
		// A replaced socket.
		assertThat(lobby.reconnect(P1)).isTrue();

		assertThat(lobbySettingsSince(mark)).isEmpty();
	}

	// ---- across games and hosts ----

	@Test
	void theSelectionSurvivesAGameAndANewJoinerAfterItGetsIt() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		start();
		playToTheEnd();

		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "lobbySettings");
		assertThat(sent.get(2).message()).isEqualTo(full());

		// Still the stored one: the same selection again is not broadcast.
		mark = outbox.mark();
		lobby.setLobbySettings(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(outbox.since(mark)).isEmpty();
	}

	// Round 2: the return to the lobby sends the stored selection to every connected member, so a
	// late joiner gets it although the host changed nothing.
	@Test
	void theReturnToTheLobbySendsTheStoredSelectionToEveryConnectedMemberIncludingALateJoiner() {
		build();
		joinThree();
		setFull(P1, List.of(P1, P2, P3));
		start();
		lobby.disconnect(P3);
		int lateMark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		assertThat(lobbySettingsSince(lateMark)).as("nothing during the game").isEmpty();

		for (int k = 0; k < 4; k++) {
			skipSong(k);
		}
		int mark = outbox.mark();
		skipSong(4);
		assertThat(lobby.phase()).as("after gameOver").isEqualTo(Room.Phase.LOBBY);
		for (String id : List.of(P1, P2, P4)) {
			assertThat(toSince(mark, id)).as("to " + id).filteredOn(s -> "lobbySettings".equals(s.type()))
					.extracting(Sent::message).containsExactly(full());
		}
		assertThat(toSince(mark, P3)).as("nothing to the member in grace").isEmpty();
		assertThat(lobbySettingsSince(mark)).extracting(Sent::to).containsExactlyInAnyOrder(P1, P2, P4);

		// The host's unchanged selection after the return is still silent.
		mark = outbox.mark();
		lobby.setLobbySettings(P1, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void withoutAStoredSelectionTheReturnToTheLobbySendsNone() {
		build();
		joinTwo();
		start();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		int mark = outbox.mark();
		playToTheEnd();
		assertThat(lobbySettingsSince(mark)).isEmpty();
	}

	@Test
	void aHostTransferKeepsTheSelectionAndTheNewHostMayChangeIt() {
		build();
		joinThree();
		setFull(P1, List.of(P1, P2, P3));
		lobby.leave(P1);
		assertThat(lobby.host()).isEqualTo(P2);

		int mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		List<Sent> sent = toSince(mark, P4);
		assertThat(types(sent)).containsExactly("joined", "roster", "lobbySettings");
		assertThat(sent.get(2).message()).isEqualTo(full());

		// The new host's equal selection is silent; its change reaches everyone; the old guest P3 is
		// not host.
		mark = outbox.mark();
		lobby.setLobbySettings(P2, 12, FILTER, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(outbox.since(mark)).isEmpty();
		mark = outbox.mark();
		lobby.setLobbySettings(P2, 20, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		assertThat(lobbySettingsSince(mark)).extracting(Sent::to).containsExactly(P2, P3, P4);
		assertThat(lobbySettingsSince(mark)).extracting(Sent::message).containsOnly(lobbySettings(20));
		rejected(P3, 15, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST, "not-host");
	}

	// ---- no game effect ----

	@Test
	void startKeepsItsOwnParameters() {
		build();
		joinTwo();
		setFull(P1, List.of(P1, P2));
		int mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		// A plain start sends no settings (no filter, chat, first) and draws 5 songs, not 12.
		assertThat(outbox.since(mark)).extracting(Sent::type).doesNotContain("settings", "lobbySettings");
		playToTheEnd();
	}

}
