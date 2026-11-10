package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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

// Cycle 071 (F-012) criterion 1: Lobby.setRoomTheme(playerId, theme) on the cycle 036 fakes (fake
// clock, manual scheduler, seeded Random, recording Outbox, real AudioTokens). The host changes the
// theme in any phase and every connected member gets {type:"roomTheme", theme}. A bad id is
// bad-room-theme (checked before not-host); a disconnected member is ignored. A joiner, a grace
// rejoin and a replaced socket get roomTheme to that player only, right after their roster
// (replace: right after the snapshot), and only when the theme is not the default. As in
// LobbyTest, every message must reach the outbox as a toPlayer (docs/cycles/071-room-themes.md,
// revisions M1, M2 and nits).
// Cycle 109 (B-031): the ids are the eight moods sunset rain sakura neon chalk pixel matsuri manga,
// default sunset. The old ids play, star and arcade are accepted and stored and broadcast as
// sunset, rain and pixel (docs/cycles/109-eight-moods.md).
class LobbyThemeTest {

	private static final String CODE = "THEME";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String P4 = "pid-4";

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
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(71), outbox,
				closedCalls::incrementAndGet);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	private static Map<String, Object> roomTheme(String theme) {
		return Map.of("type", "roomTheme", "theme", theme);
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

	private List<Sent> roomThemesSince(int mark) {
		return outbox.since(mark).stream().filter(s -> "roomTheme".equals(s.type())).toList();
	}

	private void joinThree() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(lobby.host()).isEqualTo(P1);
	}

	private void joinTwo() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
	}

	private static final List<String> MOODS =
			List.of("sunset", "rain", "sakura", "neon", "chalk", "pixel", "matsuri", "manga");

	// The host sets the theme; asserts every connected member of ids got exactly that roomTheme.
	private void setBy(String host, String theme, List<String> connected) {
		setBy(host, theme, theme, connected);
	}

	// The host sends sent; every connected member gets exactly roomTheme(broadcast).
	private void setBy(String host, String sent, String broadcast, List<String> connected) {
		int mark = outbox.mark();
		lobby.setRoomTheme(host, sent);
		for (String id : connected) {
			assertThat(toSince(mark, id)).as("to " + id + " after " + sent).extracting(Sent::message)
					.containsExactly(roomTheme(broadcast));
		}
		assertThat(outbox.since(mark)).hasSize(connected.size());
	}

	// A fresh joiner gets joined, roster and then roomTheme(theme).
	private void joinerGets(String playerId, String name, String theme) {
		int mark = outbox.mark();
		assertThat(lobby.join(playerId, name)).isEqualTo(name);
		List<Sent> sent = toSince(mark, playerId);
		assertThat(types(sent)).containsExactly("joined", "roster", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme(theme));
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

	// ---- setRoomTheme ----

	@Test
	void theHostsChangeReachesEveryConnectedMemberAndNoDisconnectedOne() {
		build();
		joinThree();
		lobby.disconnect(P3);
		int mark = outbox.mark();
		lobby.setRoomTheme(P1, "neon");
		for (String id : List.of(P1, P2)) {
			assertThat(toSince(mark, id)).as("to " + id).extracting(Sent::message).containsExactly(roomTheme("neon"));
		}
		assertThat(toSince(mark, P3)).as("nothing to the disconnected member").isEmpty();
		assertThat(outbox.since(mark)).hasSize(2);
	}

	@Test
	void everyMoodIdIsAcceptedAndReachesEveryConnectedMember() {
		build();
		joinThree();
		for (String theme : MOODS) {
			setBy(P1, theme, List.of(P1, P2, P3));
		}
	}

	@Test
	void eachNonDefaultMoodIsWhatAJoinerThenGets() {
		build();
		joinTwo();
		for (String theme : MOODS) {
			if (theme.equals("sunset")) {
				continue;
			}
			setBy(P1, theme, List.of(P1, P2));
			joinerGets(P3, "Hana", theme);
			lobby.leave(P3);
		}
	}

	@Test
	void theOldIdsAreStoredAndBroadcastAsTheirMoods() {
		build();
		joinTwo();

		setBy(P1, "star", "rain", List.of(P1, P2));
		joinerGets(P3, "Hana", "rain");
		lobby.leave(P3);

		setBy(P1, "arcade", "pixel", List.of(P1, P2));
		joinerGets(P3, "Hana", "pixel");
		lobby.leave(P3);

		// play maps to sunset, the default, so a joiner gets no roomTheme.
		setBy(P1, "play", "sunset", List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "roster");
		assertThat(roomThemesSince(mark)).isEmpty();
	}

	@Test
	void theOldIdsAreNeverBroadcastAsThemselves() {
		build();
		joinTwo();
		int mark = outbox.mark();
		for (String old : List.of("play", "star", "arcade")) {
			lobby.setRoomTheme(P1, old);
		}
		assertThat(roomThemesSince(mark)).extracting(s -> s.message().get("theme"))
				.containsExactly("sunset", "sunset", "rain", "rain", "pixel", "pixel");
	}

	@Test
	void settingTheSameThemeAgainIsBroadcastAgain() {
		build();
		joinTwo();
		setBy(P1, "chalk", List.of(P1, P2));
		setBy(P1, "chalk", List.of(P1, P2));
	}

	@Test
	void aNonHostGetsNotHostAndNothingIsBroadcast() {
		build();
		joinThree();
		int mark = outbox.mark();
		lobby.setRoomTheme(P2, "neon");
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P2);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(error("not-host"));

		// Nothing changed: a joiner on the default theme gets no roomTheme.
		mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		assertThat(types(toSince(mark, P4))).containsExactly("joined", "roster");
	}

	@Test
	void anUnknownOrNullThemeIsBadRoomThemeToTheSenderOnly() {
		build();
		joinThree();
		setBy(P1, "neon", List.of(P1, P2, P3));
		for (String bad : new String[] {"bogus", "lava", null, "", "NEON", " neon", "Play", "STAR", "arcade "}) {
			int mark = outbox.mark();
			lobby.setRoomTheme(P1, bad);
			assertThat(outbox.since(mark)).as("host " + bad).extracting(Sent::to).containsExactly(P1);
			assertThat(outbox.since(mark)).as("host " + bad).extracting(Sent::message)
					.containsExactly(error("bad-room-theme"));
		}
		// Unchanged: a joiner still gets neon.
		int mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		List<Sent> sent = toSince(mark, P4);
		assertThat(types(sent)).containsExactly("joined", "roster", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme("neon"));
	}

	@Test
	void aBadThemeFromANonHostIsBadRoomThemeBeforeNotHost() {
		build();
		joinThree();
		for (String bad : new String[] {"bogus", "lava", null}) {
			int mark = outbox.mark();
			lobby.setRoomTheme(P2, bad);
			assertThat(outbox.since(mark)).as("guest " + bad).extracting(Sent::to).containsExactly(P2);
			assertThat(outbox.since(mark)).as("guest " + bad).extracting(Sent::message)
					.containsExactly(error("bad-room-theme"));
		}
	}

	@Test
	void aGuestGetsNotHostForEveryValidIdIncludingTheOldOnes() {
		build();
		joinThree();
		List<String> valid = new ArrayList<>(MOODS);
		valid.addAll(List.of("play", "star", "arcade"));
		for (String theme : valid) {
			int mark = outbox.mark();
			lobby.setRoomTheme(P2, theme);
			assertThat(outbox.since(mark)).as("guest " + theme).extracting(Sent::to).containsExactly(P2);
			assertThat(outbox.since(mark)).as("guest " + theme).extracting(Sent::message)
					.containsExactly(error("not-host"));
		}
	}

	@Test
	void aDisconnectedMembersSetRoomThemeIsIgnored() {
		build();
		joinThree();
		lobby.disconnect(P2);
		int mark = outbox.mark();
		lobby.setRoomTheme(P2, "neon");
		assertThat(outbox.since(mark)).isEmpty();

		// The host who disconnects is ignored too.
		lobby.disconnect(P1);
		mark = outbox.mark();
		lobby.setRoomTheme(P1, "pixel");
		assertThat(roomThemesSince(mark)).isEmpty();
		assertThat(toSince(mark, P1)).isEmpty();
	}

	// ---- joiners ----

	@Test
	void aLobbyJoinerAfterAChangeGetsJoinedRosterThenRoomTheme() {
		build();
		joinTwo();
		setBy(P1, "rain", List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme("rain"));
		assertThat(roomThemesSince(mark)).as("only the joiner gets roomTheme").hasSize(1);
		for (String id : List.of(P1, P2)) {
			assertThat(types(toSince(mark, id))).as("to " + id).containsExactly("roster");
		}
	}

	@Test
	void onTheDefaultThemeNoJoinPathSendsRoomTheme() {
		build();
		int start = outbox.mark();
		joinTwo();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");

		// Lobby: a joiner.
		int mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		assertThat(types(toSince(mark, P4))).containsExactly("joined", "roster");

		// Lobby: a grace rejoin and a replaced socket.
		lobby.disconnect(P4);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P4)).isTrue();
		assertThat(types(toSince(mark, P4))).containsExactly("joined", "snapshot", "roster");
		mark = outbox.mark();
		assertThat(lobby.reconnect(P4)).isTrue();
		assertThat(types(outbox.since(mark))).containsExactly("joined", "snapshot");

		// In a game: a late joiner, a grace rejoin and a replaced socket.
		lobby.leave(P4);
		lobby.leave(P3);
		start();
		mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		assertThat(types(toSince(mark, P4))).containsExactly("joined", "snapshot", "roster");
		lobby.disconnect(P2);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(types(toSince(mark, P2))).containsExactly("joined", "snapshot", "roster");
		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(types(outbox.since(mark))).containsExactly("joined", "snapshot");

		assertThat(roomThemesSince(start)).isEmpty();
	}

	@Test
	void aThemeSetBackToSunsetIsNotSentToJoiners() {
		build();
		joinTwo();
		setBy(P1, "neon", List.of(P1, P2));
		setBy(P1, "sunset", List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "roster");
	}

	@Test
	void aGraceRejoinInTheLobbyGetsRoomThemeRightAfterItsRoster() {
		build();
		joinThree();
		setBy(P1, "rain", List.of(P1, P2, P3));
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("rain"));
		assertThat(roomThemesSince(mark)).hasSize(1);
	}

	@Test
	void aGraceRejoinInAGameGetsRoomThemeRightAfterItsRoster() {
		build();
		joinThree();
		setBy(P1, "pixel", List.of(P1, P2, P3));
		start();
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("pixel"));
		assertThat(roomThemesSince(mark)).hasSize(1);
	}

	@Test
	void aRejoinThatResumesAPausedGameGetsRoomThemeBeforeTheResume() {
		build();
		joinTwo();
		setBy(P1, "chalk", List.of(P1, P2));
		start();
		for (String id : List.of(P1, P2)) {
			lobby.ready(id, 0);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		List<Sent> sent = toSince(mark, P1);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme", "prepare");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("chalk"));
	}

	@Test
	void aReplacedSocketInTheLobbyGetsJoinedSnapshotThenRoomTheme() {
		build();
		joinTwo();
		setBy(P1, "neon", List.of(P1, P2));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P2, P2, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme("neon"));
	}

	@Test
	void aReplacedSocketInAGameGetsJoinedSnapshotThenRoomTheme() {
		build();
		joinTwo();
		setBy(P1, "rain", List.of(P1, P2));
		start();
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P2, P2, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme("rain"));
	}

	@Test
	void aLateJoinerInAGameGetsRoomThemeRightAfterItsRoster() {
		build();
		joinTwo();
		setBy(P1, "sakura", List.of(P1, P2));
		start();
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("sakura"));
		assertThat(roomThemesSince(mark)).hasSize(1);
	}

	// ---- across games ----

	@Test
	void theThemeSurvivesAGameAndTheReturnToTheLobby() {
		build();
		joinTwo();
		setBy(P1, "rain", List.of(P1, P2));
		start();
		for (int k = 0; k < 5; k++) {
			skipSong(k);
		}
		assertThat(lobby.phase()).as("after gameOver").isEqualTo(Room.Phase.LOBBY);

		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "roster", "roomTheme");
		assertThat(sent.get(2).message()).isEqualTo(roomTheme("rain"));

		// And into the next game.
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		sent = toSince(mark, P4);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("rain"));
	}

	@Test
	void settingTheThemeDuringAGameIsAcceptedAndTheGameGoesOn() {
		build();
		joinThree();
		lobby.disconnect(P3);
		start();
		setBy(P1, "neon", List.of(P1, P2));
		for (String id : List.of(P1, P2)) {
			lobby.ready(id, 0);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		setBy(P1, "pixel", List.of(P1, P2));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		// A non-host during the game still gets not-host.
		int mark = outbox.mark();
		lobby.setRoomTheme(P2, "chalk");
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(error("not-host"));

		// The rejoining member sees the theme set while it was away.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "roster", "roomTheme");
		assertThat(sent.get(3).message()).isEqualTo(roomTheme("pixel"));
	}

}
