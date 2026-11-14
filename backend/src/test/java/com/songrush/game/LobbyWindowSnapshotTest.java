package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 128 (B-036, D-129) criterion 5: the snapshot in the reveal window through the Lobby, on the
// cycle 036 fakes (docs/cycles/128-window-snapshot.md). The Lobby is built with the constructor that
// takes the play-to-limit switch; prefetch is off.
//
// Every song lasts at least 90 s; the song is decided by Mina's correct chat 10 s after startAt, which
// opens the window until deadlineAt. needed in the snapshot is the room's plain count: the connected
// Room players when the snapshot is built, which for a member returning from grace does not yet count
// that member (Lobby.reconnect marks the Room connection after the snapshot).
// As in LobbyReconnectTest, every message must reach the outbox as a toPlayer.
class LobbyWindowSnapshotTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String P4 = "pid-4";

	private static final List<String> IDS = List.of(P1, P2, P3, P4);

	private static final List<String> NAMES = List.of("Mina", "Joon", "Hana", "Sora");

	private static final long[] LONG_SONGS = {90000, 120000, 95000, 100000, 110000, 130000, 91000};

	private static final long MAX_PLAY_MS = Room.MAX_PLAY.toMillis();

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

	private List<String> members;

	private void build(boolean playToLimit) {
		catalog = GameFixture.build(dir, LONG_SONGS, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(128), outbox,
				closedCalls::incrementAndGet, CoverMap.empty(), false, playToLimit);
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

	private Map<String, Object> lastTo(String playerId, String type) {
		List<Sent> sent = outbox.all().stream().filter(s -> playerId.equals(s.to()) && type.equals(s.type())).toList();
		assertThat(sent).as(type + " to " + playerId).isNotEmpty();
		return sent.get(sent.size() - 1).message();
	}

	private static Map<String, Object> assertSnapshot(Map<String, Object> snapshot) {
		assertThat(snapshot.get("type")).isEqualTo("snapshot");
		assertThat(snapshot.keySet()).as("snapshot keys").containsExactlyInAnyOrder("type", "phase", "players",
				"song", "scores");
		assertThat(MAPPER.writeValueAsString(snapshot)).as("no player id").doesNotContain("pid-");
		return snapshot;
	}

	private Map<String, Object> assertJoinedThenSnapshot(int mark, String playerId, String name) {
		List<Sent> sent = toSince(mark, playerId);
		assertThat(sent.size()).as("messages to " + playerId).isGreaterThanOrEqualTo(2);
		assertThat(types(sent.subList(0, 2))).containsExactly("joined", "snapshot");
		assertThat(sent.get(0).message()).containsEntry("code", CODE).containsEntry("name", name);
		return assertSnapshot(sent.get(1).message());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> song(Map<String, Object> snapshot) {
		Object song = snapshot.get("song");
		assertThat(song).as("snapshot song").isInstanceOf(Map.class);
		return (Map<String, Object>) song;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> mapIn(Map<String, Object> song, String key) {
		Object value = song.get(key);
		assertThat(value).as(key).isInstanceOf(Map.class);
		return (Map<String, Object>) value;
	}

	private Anime currentAnime() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private void joinAll(int count) {
		members = IDS.subList(0, count);
		for (int i = 0; i < count; i++) {
			assertThat(lobby.join(IDS.get(i), NAMES.get(i))).isEqualTo(NAMES.get(i));
		}
	}

	// From LOBBY: starts a 5-song game and plays song 0 until PLAYING; returns its start frame.
	private Map<String, Object> startAndPlay() {
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		for (String id : members) {
			lobby.ready(id, 0);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		Map<String, Object> start = outbox.last("start").message();
		assertThat(num(start, "deadlineAt") - num(start, "startAt")).as("a song of at least 90 s")
				.isEqualTo(MAX_PLAY_MS);
		return start;
	}

	// Mina answers 10 s after startAt; returns the reveal frame as Mina got it.
	private Map<String, Object> decideAtTenSeconds(Map<String, Object> start) {
		long at = num(start, "startAt") + 10_000;
		scheduler.advance(Duration.ofMillis(at - clock.millis()));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		int mark = outbox.mark();
		lobby.chat(P1, currentAnime().titles().get("en").get(0));
		assertThat(types(toSince(mark, P1))).as("answered").contains("correct", "reveal");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		return lastTo(P1, "reveal");
	}

	// How many members are connected now, by the last roster sent to that connected member.
	@SuppressWarnings("unchecked")
	private int connectedByRoster(String to) {
		int connected = 0;
		for (Object row : (List<Object>) lastTo(to, "roster").get("players")) {
			if (Boolean.TRUE.equals(((Map<String, Object>) row).get("connected"))) {
				connected++;
			}
		}
		return connected;
	}

	private static Map<String, Object> withoutType(Map<String, Object> reveal) {
		Map<String, Object> copy = new LinkedHashMap<>(reveal);
		assertThat(copy.remove("type")).isEqualTo("reveal");
		return copy;
	}

	// The window song: the start's values, the reveal frame without type and skipState {0, needed}.
	private static void assertWindowSong(Map<String, Object> song, Map<String, Object> start,
			Map<String, Object> reveal, int needed) {
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt",
				"reveal", "skipState");
		assertThat(num(song, "index")).isEqualTo(0);
		assertThat(num(song, "total")).isEqualTo(5);
		assertThat(num(song, "startAt")).isEqualTo(num(start, "startAt"));
		assertThat(num(song, "deadlineAt")).isEqualTo(num(start, "deadlineAt"));
		assertThat(song.get("reveal")).as("reveal is the broadcast frame without type").isEqualTo(withoutType(reveal));
		Map<String, Object> skipState = mapIn(song, "skipState");
		assertThat(skipState.keySet()).containsExactlyInAnyOrder("votes", "needed");
		assertThat(num(skipState, "votes")).as("votes").isEqualTo(0);
		assertThat(num(skipState, "needed")).as("needed").isEqualTo(needed);
	}

	// ---- criterion 5 ----

	// Three members, and four so that the plain count (3 -> 2) differs from one that counts the
	// returning member (4 -> 3).
	@ParameterizedTest
	@ValueSource(ints = {3, 4})
	void aMemberReturningFromGraceInTheWindowGetsTheRevealAndSkipState(int count) {
		build(true);
		joinAll(count);
		Map<String, Object> start = startAndPlay();
		String audioUrl = (String) outbox.last("prepare").message().get("audioUrl");
		scheduler.advance(Duration.ofSeconds(3));
		lobby.disconnect(P3);
		Map<String, Object> reveal = decideAtTenSeconds(start);
		assertThat(num(reveal, "playUntil")).isEqualTo(num(start, "deadlineAt"));
		scheduler.advance(Duration.ofSeconds(5));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);

		int connected = connectedByRoster(P1);
		assertThat(connected).as("Hana is still away").isEqualTo(count - 1);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing");
		Map<String, Object> song = song(snapshot);
		assertWindowSong(song, start, reveal, connected / 2 + 1);
		assertThat(song.get("audioUrl")).isEqualTo(audioUrl);
	}

	@Test
	void aReplacedSocketInTheWindowGetsTheRevealAndSkipState() {
		build(true);
		joinAll(3);
		Map<String, Object> start = startAndPlay();
		Map<String, Object> reveal = decideAtTenSeconds(start);
		scheduler.advance(Duration.ofSeconds(5));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);

		int connected = connectedByRoster(P1);
		assertThat(connected).isEqualTo(3);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P2, "Joon");
		assertWindowSong(song(snapshot), start, reveal, connected / 2 + 1);
	}

	@Test
	void aLateJoinerInTheWindowGetsASnapshotWithTheReveal() {
		build(true);
		joinAll(3);
		Map<String, Object> start = startAndPlay();
		Map<String, Object> reveal = decideAtTenSeconds(start);
		scheduler.advance(Duration.ofSeconds(5));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);

		int connected = connectedByRoster(P1);
		int mark = outbox.mark();
		assertThat(lobby.join(P4, "Sora")).isEqualTo("Sora");
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P4, "Sora");
		Map<String, Object> song = song(snapshot);
		assertThat(song.get("reveal")).as("the late joiner gets the reveal").isEqualTo(withoutType(reveal));
		// The joiner is not yet in the Room when its snapshot is built: the count of the three players.
		assertWindowSong(song, start, reveal, connected / 2 + 1);
	}

	@Test
	void switchOffAReturnFromGraceInRevealGetsNoSong() {
		build(false);
		joinAll(3);
		Map<String, Object> start = startAndPlay();
		scheduler.advance(Duration.ofSeconds(3));
		lobby.disconnect(P3);
		Map<String, Object> reveal = decideAtTenSeconds(start);
		assertThat(reveal).doesNotContainKey("playUntil");
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing").containsKey("song");
		assertThat(snapshot.get("song")).isNull();
	}

}
