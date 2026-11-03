package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
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

// Cycle 060 (B-011) criterion 3b: Lobby.start(playerId, songCount, DrawFilter) on the cycle 036
// fakes. Every player who gets joined + snapshot while a game with a non-empty filter runs (a late
// joiner, a rejoin within grace, a replaced socket) gets settings right after the snapshot, once;
// with no filter nobody gets settings. As in LobbyTest, every message must reach the outbox as a
// toPlayer (docs/cycles/060-draw-filters.md, review H2).
class LobbyFilterTest {

	private static final String CODE = "FILTR";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	// Even OP / odd ED, year 2000 + i.
	private static final List<FilterFixture.Entry> ENTRIES = entries();

	// OP of 2000..2010: indexes 0, 2, 4, 6, 8, 10.
	private static final DrawFilter FILTER = new DrawFilter(Set.of("OP"), 2000, 2010);

	private static final Map<String, Object> SETTINGS = settingsMessage();

	private static List<FilterFixture.Entry> entries() {
		List<FilterFixture.Entry> out = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			out.add(i % 2 == 0 ? FilterFixture.op(2000 + i) : FilterFixture.ed(2000 + i));
		}
		return out;
	}

	private static Map<String, Object> settingsMessage() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "settings");
		m.put("types", List.of("OP"));
		m.put("yearFrom", 2000);
		m.put("yearTo", 2010);
		return m;
	}

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
		catalog = FilterFixture.build(dir, ENTRIES);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(60), outbox,
				closedCalls::incrementAndGet);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private List<Sent> toSince(int mark, String playerId) {
		return outbox.since(mark).stream().filter(s -> playerId.equals(s.to())).toList();
	}

	private List<Sent> settingsSince(int mark) {
		return outbox.since(mark).stream().filter(s -> "settings".equals(s.type())).toList();
	}

	private void joinTwo() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
	}

	private void startWithFilter() {
		lobby.start(P1, 5, FILTER);
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

	// ---- start ----

	@Test
	void everyMemberGetsTheSameSettingsBeforeTheFirstPrepare() {
		build();
		joinTwo();
		int mark = outbox.mark();
		startWithFilter();
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("to " + id).containsExactly("settings", "prepare", "roster");
			assertThat(sent.get(0).message()).isEqualTo(SETTINGS);
		}
		assertThat(settingsSince(mark)).hasSize(2);
	}

	@Test
	void aTooNarrowFilterGivesEveryMemberTheAvailableCountAndStaysInTheLobby() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, new DrawFilter(Set.of("ED"), 2100, null));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		for (String id : List.of(P1, P2)) {
			assertThat(toSince(mark, id)).extracting(Sent::message)
					.containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", 0));
		}
		// Back in the lobby: a joiner gets no settings.
		int joinMark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(joinMark, P3))).containsExactly("joined", "roster");
		assertThat(settingsSince(mark)).isEmpty();
	}

	// ---- joiners during a filtered game ----

	@Test
	void aLateJoinerGetsSettingsRightAfterItsSnapshotOnce() {
		build();
		joinTwo();
		startWithFilter();
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(SETTINGS);
		assertThat(settingsSince(mark)).as("only the joiner gets settings").hasSize(1);

		// Promoted at the next prepare: no second settings.
		skipSong(0);
		assertThat(toSince(mark, P3).stream().filter(s -> "prepare".equals(s.type())).toList()).hasSize(1);
		assertThat(settingsSince(mark)).hasSize(1);
	}

	@Test
	void aMemberWhoRejoinsWithinGraceGetsSettingsRightAfterItsSnapshotOnce() {
		build();
		joinTwo();
		startWithFilter();
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(SETTINGS);
		assertThat(settingsSince(mark)).hasSize(1);
	}

	@Test
	void aMemberInGraceAtStartWhoRejoinsGetsSettingsRightAfterItsSnapshotOnce() {
		build();
		joinTwo();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		startWithFilter();
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(SETTINGS);
		assertThat(settingsSince(mark)).hasSize(1);
	}

	@Test
	void aReplacedSocketGetsSettingsRightAfterItsSnapshotOnce() {
		build();
		joinTwo();
		startWithFilter();
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P2, P2, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings");
		assertThat(sent.get(2).message()).isEqualTo(SETTINGS);
	}

	// ---- no filter ----

	@Test
	void withNoFilterNoJoinPathSendsSettings() {
		assertNoSettingsOnAnyJoinPath(false);
	}

	@Test
	void withAnExplicitNoneNoJoinPathSendsSettings() {
		assertNoSettingsOnAnyJoinPath(true);
	}

	private void assertNoSettingsOnAnyJoinPath(boolean explicit) {
		build();
		int start = outbox.mark();
		joinTwo();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		if (explicit) {
			lobby.start(P1, 5, DrawFilter.NONE);
		}
		else {
			lobby.start(P1, 5);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		// In grace at start, then rejoins.
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");

		// Rejoin within grace.
		lobby.disconnect(P2);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(types(toSince(mark, P2))).containsExactly("joined", "snapshot", "roster");

		// Replaced socket.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(types(outbox.since(mark))).containsExactly("joined", "snapshot");

		// Late joiner: P3 leaves and a new id joins.
		lobby.leave(P3);
		mark = outbox.mark();
		assertThat(lobby.join("pid-4", "Sora")).isEqualTo("Sora");
		assertThat(types(toSince(mark, "pid-4"))).containsExactly("joined", "snapshot", "roster");

		assertThat(settingsSince(start)).isEmpty();
	}

	@Test
	void theFilterHoldsOnlyForItsGame() {
		build();
		joinTwo();
		startWithFilter();
		for (int k = 0; k < 5; k++) {
			skipSong(k);
		}
		assertThat(lobby.phase()).as("after gameOver").isEqualTo(Room.Phase.LOBBY);

		int mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");
		assertThat(settingsSince(mark)).as("the second game has no filter").isEmpty();
	}

}
