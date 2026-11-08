package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.cover.CoverMap;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 093 (B-020) criterion 2 at Lobby level, on the cycle 036 fakes: a Lobby built with a cover
// map (every fixture anime has a cover) plays a whole game while players join, reconnect (in grace
// and as a replaced socket) and late-join during PREPARE, PLAYING and REVEAL. Every outbound text is
// searched for the cover ids: only a reveal may name one, and only its own song's
// (docs/cycles/093-reveal-cover.md). As in LobbyTest, every message is a toPlayer.
class LobbyCoverTest {

	private static final String CODE = "COVER";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String P4 = "pid-4";

	private static final String P5 = "pid-5";

	private static final String P6 = "pid-6";

	private static final String P7 = "pid-7";

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	// Cover ids by fixture anime index.
	private static final List<String> IDS = List.of("Cq1Wm8Zt3Lp0Vb6Nk2Rx9A", "Dw2Xn7Yu4Ko1Uc5Mj3Sy8B",
			"Ev3Yo6Xi5Jp2Td4Lh4Tz7C", "Fx4Zp5Wo6Iq3Se3Kg5Ua6D", "Gy5Aq4Vn7Hr4Rf2Jf6Vb5E");

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

	// Fixture anime index per song index, recorded at each prepare.
	private final List<Integer> order = new ArrayList<>();

	private CoverMap covers() {
		try {
			Path cache = Files.createDirectories(dir.resolve("cover-cache"));
			List<String> entries = new ArrayList<>();
			for (int i = 0; i < IDS.size(); i++) {
				int size = 20 + i;
				Files.write(cache.resolve(IDS.get(i) + ".jpg"), new byte[size]);
				entries.add("\"%d\": {\"id\": \"%s\", \"file\": \"%s.jpg\", \"size\": %d}"
						.formatted(GameFixture.animeId(i), IDS.get(i), IDS.get(i), size));
			}
			Files.writeString(cache.resolve("index.json"),
					"{\"version\": 1, \"covers\": {" + String.join(", ", entries) + "}}", StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		CoverMap map = CoverMap.loadDisk(dir.resolve("cover-cache"));
		assertThat(map.size()).as("fixture cover map").isEqualTo(IDS.size());
		return map;
	}

	private void build(CoverMap covers) {
		catalog = GameFixture.build(dir, FIVE, 0);
		lobby = covers == null
				? new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(93), outbox,
						closedCalls::incrementAndGet)
				: new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(93), outbox,
						closedCalls::incrementAndGet, covers);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	private static String json(Object value) {
		return MAPPER.writeValueAsString(value);
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

	// Records the fixture anime of the song just prepared.
	private void recordPrepared(int index) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(((Number) outbox.last("prepare").message().get("index")).intValue()).isEqualTo(index);
		assertThat(order).hasSize(index);
		order.add(currentAnime().id() - GameFixture.ANIME_BASE);
	}

	private void ready(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
	}

	private void advanceToDeadline() {
		long deadline = ((Number) outbox.last("start").message().get("deadlineAt")).longValue();
		scheduler.advance(Duration.ofMillis(deadline - clock.millis()));
	}

	// A dropped connection and its reconnect within the grace period.
	private void dropAndReconnect(String id) {
		lobby.disconnect(id);
		assertThat(lobby.reconnect(id)).isTrue();
	}

	// Nothing sent since the mark names any cover, a cover path or coverUrl.
	private void assertNoCoverSince(int mark, String when) {
		for (Sent sent : outbox.since(mark)) {
			String text = json(sent.message());
			assertThat(text).as(when + ": " + sent.type() + " to " + sent.to()).doesNotContain("coverUrl")
					.doesNotContain("/cover/");
			for (String id : IDS) {
				assertThat(text).as(when + ": " + sent.type() + " to " + sent.to()).doesNotContain(id);
			}
		}
	}

	// The reveals sent since the mark: one per connected player, each with this song's coverUrl.
	private void assertRevealsSince(int mark, int index, Set<String> connected) {
		List<Sent> reveals = outbox.since(mark).stream().filter(s -> "reveal".equals(s.type())).toList();
		assertThat(reveals.stream().map(Sent::to).toList()).as("reveal recipients")
				.containsExactlyInAnyOrderElementsOf(connected);
		String expected = "/cover/" + IDS.get(order.get(index));
		for (Sent reveal : reveals) {
			assertThat(((Number) reveal.message().get("index")).intValue()).isEqualTo(index);
			assertThat(reveal.message().get("coverUrl")).as("reveal to " + reveal.to()).isEqualTo(expected);
			String text = json(reveal.message());
			for (String id : IDS) {
				if (!expected.endsWith(id)) {
					assertThat(text).as("reveal %d names another song's cover", index).doesNotContain(id);
				}
			}
		}
	}

	// ---- criterion 2 ----

	@Test
	void noCoverLeavesBeforeItsRevealThroughJoinsAndReconnects() {
		build(covers());
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		int mark = outbox.mark();
		lobby.start(P1, 5);

		// Song 0, PREPARE: a reconnect from grace, a replaced socket, a late joiner.
		recordPrepared(0);
		dropAndReconnect(P2);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.chat(P1, "too early");
		ready(0, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);

		// Song 0, PLAYING: the same again, plus chat, before and after startAt.
		dropAndReconnect(P2);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.join(P4, "Dora")).isEqualTo("Dora");
		lobby.chat(P2, "before start");
		scheduler.advance(Room.LEAD);
		lobby.chat(P1, "a wrong guess");
		lobby.chat(P3, "a watcher's guess");
		dropAndReconnect(P3);
		assertNoCoverSince(mark, "before reveal 0");

		int revealMark = outbox.mark();
		advanceToDeadline();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		assertRevealsSince(revealMark, 0, new LinkedHashSet<>(List.of(P1, P2, P3, P4)));

		// Song 0, REVEAL: snapshots and a join while the cover is already shown.
		mark = outbox.mark();
		dropAndReconnect(P2);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.join(P5, "Eve")).isEqualTo("Eve");
		assertNoCoverSince(mark, "during reveal 0");

		// Song 1: the next prepare carries no cover id, the previous one included.
		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		recordPrepared(1);
		dropAndReconnect(P3);
		assertThat(lobby.reconnect(P4)).isTrue();
		assertThat(lobby.join(P6, "Ari")).isEqualTo("Ari");
		ready(1, P1, P2, P3, P4, P5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		dropAndReconnect(P4);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.join(P7, "Bo")).isEqualTo("Bo");
		scheduler.advance(Room.LEAD);
		lobby.chat(P5, "a wrong guess");
		assertNoCoverSince(mark, "before reveal 1");

		revealMark = outbox.mark();
		lobby.chat(P2, currentAnime().titles().get("en").get(0));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		assertRevealsSince(revealMark, 1, new LinkedHashSet<>(List.of(P1, P2, P3, P4, P5, P6, P7)));

		// Songs 2-4 run on their timers; a reconnect in each PREPARE and PLAYING.
		for (int index = 2; index < 5; index++) {
			mark = outbox.mark();
			scheduler.advance(Room.REVEAL_PAUSE);
			recordPrepared(index);
			dropAndReconnect(P6);
			scheduler.advance(Room.PREPARE_TIMEOUT);
			assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
			dropAndReconnect(P7);
			assertNoCoverSince(mark, "before reveal " + index);
			revealMark = outbox.mark();
			advanceToDeadline();
			assertRevealsSince(revealMark, index, new LinkedHashSet<>(List.of(P1, P2, P3, P4, P5, P6, P7)));
		}
		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.since(mark).stream().map(Sent::type).toList()).contains("gameOver");
		dropAndReconnect(P2);
		assertNoCoverSince(mark, "after the game");

		assertThat(order).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
		// Over the whole run: only reveals ever name a cover.
		for (Sent sent : outbox.all()) {
			if (!"reveal".equals(sent.type())) {
				String text = json(sent.message());
				for (String id : IDS) {
					assertThat(text).as(sent.type() + " to " + sent.to()).doesNotContain(id);
				}
			}
		}
		assertThat(closedCalls).hasValue(0);
	}

	@Test
	void theOldConstructorRevealsWithANullCoverUrl() {
		build(null);
		lobby.join(P1, "Mina");
		lobby.start(P1, 5);
		ready(0, P1);
		advanceToDeadline();
		Map<String, Object> reveal = outbox.last("reveal").message();
		assertThat(reveal).containsKey("coverUrl");
		assertThat(reveal.get("coverUrl")).isNull();
	}

}
