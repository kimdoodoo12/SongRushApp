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
import com.songrush.cover.CoverMap;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 107 (D-119) at Lobby level, on the cycle 036 fakes: the early mode reaches every player
// through the lobby, and a reconnect or a join during the reveal (docs/cycles/107-prefetch-on-reveal.md,
// backend items 1, 2 and 5). As in LobbyTest, every message must reach the outbox as a toPlayer.
//
// API this test pins (new; the existing constructors stay and mean prefetchOnReveal = false):
//   public Lobby(String code, Catalog catalog, AudioTokens tokens, GameScheduler scheduler, Clock clock,
//       RandomGenerator random, Outbox outbox, Runnable onClosed, CoverMap covers,
//       boolean prefetchOnReveal)
// which passes prefetchOnReveal to every Room it builds (Room's new 9-argument constructor, see
// RoomPrefetchOnRevealTest).
class LobbyPrefetchOnRevealTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	private static final long REVEAL_MS = Room.REVEAL_PAUSE.toMillis();

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private final AtomicInteger closedCalls = new AtomicInteger();

	private Catalog catalog;

	private Lobby lobby;

	private void build(boolean prefetchOnReveal) {
		catalog = GameFixture.build(dir, SEVEN, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(107), outbox,
				closedCalls::incrementAndGet, CoverMap.empty(), prefetchOnReveal);
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

	private String exactTitle() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return catalog.anime(catalog.theme(themeId).orElseThrow().animeId()).orElseThrow().titles().get("en").get(0);
	}

	// Mina and Joon start a 5-song game, play song 0 and Mina wins it. Returns the clock at the reveal.
	private long twoPlaySongZeroToReveal() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		lobby.start(P1, 5);
		lobby.ready(P1, 0);
		lobby.ready(P2, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		long startAt = num(outbox.last("start").message(), "startAt");
		scheduler.advance(Duration.ofMillis(startAt - clock.millis()));
		lobby.chat(P1, exactTitle());
		assertThat(outbox.ofType("reveal")).isNotEmpty();
		return clock.millis();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> snapshotSong(Map<String, Object> snapshot) {
		assertThat(snapshot.get("type")).isEqualTo("snapshot");
		Object song = snapshot.get("song");
		assertThat(song).as("snapshot song").isInstanceOf(Map.class);
		return (Map<String, Object>) song;
	}

	// ---- wiring ----

	@Test
	void eachPlayerGetsTheEarlyPrepareRightAfterRevealAndScores() {
		build(true);
		int mark = outbox.mark();
		twoPlaySongZeroToReveal();
		for (String id : List.of(P1, P2)) {
			List<String> t = types(toSince(mark, id));
			int reveal = t.lastIndexOf("reveal");
			assertThat(t.subList(reveal, t.size())).as("to " + id).containsExactly("reveal", "scores", "prepare");
			Map<String, Object> prepare = toSince(mark, id).get(t.size() - 1).message();
			assertThat(prepare.keySet()).containsExactlyInAnyOrder("type", "index", "total", "audioUrl", "holdMs");
			assertThat(num(prepare, "index")).isEqualTo(1);
			assertThat(num(prepare, "holdMs")).isEqualTo(REVEAL_MS);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void prefetchOffWithTheNewConstructorSendsNoPrepareDuringTheReveal() {
		build(false);
		int mark = outbox.mark();
		twoPlaySongZeroToReveal();
		List<String> t = types(outbox.since(mark));
		assertThat(t.subList(t.lastIndexOf("reveal"), t.size())).doesNotContain("prepare");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void everyoneReadyDuringTheRevealStartsExactlyAtTheRevealEnd() {
		build(true);
		long revealAt = twoPlaySongZeroToReveal();
		lobby.ready(P1, 1);
		lobby.ready(P2, 1);
		int mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE.minusMillis(1));
		assertThat(types(outbox.since(mark))).isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("start", "start");
		Map<String, Object> start = outbox.last("start").message();
		assertThat(num(start, "serverTime")).isEqualTo(revealAt + REVEAL_MS);
		assertThat(num(start, "startAt")).isEqualTo(revealAt + REVEAL_MS + Room.LEAD.toMillis());
	}

	// ---- reconnect and join during the reveal ----

	@Test
	void aReconnectDuringTheRevealGetsTheNextSongWithoutHoldMs() {
		build(true);
		twoPlaySongZeroToReveal();
		Map<String, Object> prepare = outbox.last("prepare").message();
		lobby.disconnect(P2);
		scheduler.advance(Duration.ofSeconds(1));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent.subList(0, 2))).containsExactly("joined", "snapshot");
		Map<String, Object> song = snapshotSong(sent.get(1).message());
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		for (Sent s : sent) {
			assertThat(s.message()).as(s.type() + " to the reconnecting player").doesNotContainKey("holdMs");
		}
	}

	@Test
	void aJoinDuringTheRevealIsPromotedAtOnceAndGetsItsOwnPrepareWithTheRemainingHold() {
		build(true);
		long revealAt = twoPlaySongZeroToReveal();
		Map<String, Object> early = outbox.last("prepare").message();
		scheduler.advance(Duration.ofMillis(1500));
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		List<String> t = types(sent);
		assertThat(t.subList(0, 2)).containsExactly("joined", "snapshot");
		Map<String, Object> song = snapshotSong(sent.get(1).message());
		assertThat(song).doesNotContainKey("holdMs");
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(t).containsOnlyOnce("prepare");
		assertThat(t.indexOf("prepare")).as("prepare after the snapshot").isGreaterThan(1);
		Map<String, Object> own = sent.get(t.indexOf("prepare")).message();
		assertThat(own.keySet()).containsExactlyInAnyOrder("type", "index", "total", "audioUrl", "holdMs");
		assertThat(num(own, "index")).isEqualTo(1);
		assertThat(num(own, "holdMs")).isEqualTo(REVEAL_MS - 1500);
		assertThat(own.get("audioUrl")).isEqualTo(early.get("audioUrl"));
		assertThat(toSince(mark, P1)).as("the others get no prepare").noneMatch(s -> "prepare".equals(s.type()));

		// Hana plays song 1: all three ready during the reveal start it at the reveal end.
		lobby.ready(P1, 1);
		lobby.ready(P2, 1);
		lobby.ready(P3, 1);
		mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(revealAt + REVEAL_MS - clock.millis()));
		assertThat(types(outbox.since(mark))).containsExactly("start", "start", "start");
		assertThat(num(outbox.last("start").message(), "serverTime")).isEqualTo(revealAt + REVEAL_MS);
	}

	@Test
	void aMemberInGraceAtStartWhoReconnectsDuringTheRevealIsPromotedAtOnceWithTheRemainingHold() {
		build(true);
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.disconnect(P3);
		long revealAt = twoPlaySongZeroToReveal();
		scheduler.advance(Duration.ofSeconds(4));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		List<Sent> sent = toSince(mark, P3);
		List<String> t = types(sent);
		assertThat(t.subList(0, 2)).containsExactly("joined", "snapshot");
		assertThat(t).containsOnlyOnce("prepare");
		Map<String, Object> own = sent.get(t.indexOf("prepare")).message();
		assertThat(num(own, "index")).isEqualTo(1);
		assertThat(num(own, "holdMs")).isEqualTo(REVEAL_MS - 4000);

		lobby.ready(P1, 1);
		lobby.ready(P2, 1);
		mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(revealAt + REVEAL_MS - clock.millis()));
		assertThat(types(outbox.since(mark))).as("Hana is a Room player now and not ready").isEmpty();
		lobby.ready(P3, 1);
		assertThat(types(outbox.since(mark))).containsExactly("start", "start", "start");
	}

	@Test
	void everyoneLeavingDuringTheRevealPausesAndTheReturnReprepareWithoutHoldMs() {
		build(true);
		twoPlaySongZeroToReveal();
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofSeconds(10));
		assertThat(types(outbox.since(mark))).doesNotContain("start", "prepare");
		assertThat(lobby.reconnect(P1)).isTrue();
		List<Sent> sent = toSince(mark, P1);
		List<String> t = types(sent);
		assertThat(t).containsOnlyOnce("prepare");
		Map<String, Object> again = sent.get(t.indexOf("prepare")).message();
		assertThat(again.keySet()).containsExactlyInAnyOrder("type", "index", "total", "audioUrl");
		assertThat(num(again, "index")).isEqualTo(1);
		mark = outbox.mark();
		long now = clock.millis();
		lobby.ready(P1, 1);
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(outbox.last("start").message(), "serverTime")).isEqualTo(now);
	}

}
