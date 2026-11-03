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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 040 (B-005a) criterion 2: the Lobby on the cycle 036 fakes (fake clock, manual scheduler,
// seeded Random, recording Outbox, real AudioTokens). Every message must reach the outbox as a
// toPlayer (the delivery contract), which the @AfterEach check enforces for every test
// (docs/cycles/040-rooms-lobby.md).
class LobbyTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final String P4 = "pid-4";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

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

	private void build(long[] durations) {
		catalog = GameFixture.build(dir, durations, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(40), outbox,
				closedCalls::incrementAndGet);
	}

	private void build() {
		build(SEVEN);
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

	private List<Sent> to(String playerId) {
		return toSince(0, playerId);
	}

	private Map<String, Object> lastTo(String playerId) {
		List<Sent> sent = to(playerId);
		assertThat(sent).as("messages to " + playerId).isNotEmpty();
		return sent.get(sent.size() - 1).message();
	}

	private Map<String, Object> lastRosterTo(String playerId) {
		List<Sent> rosters = to(playerId).stream().filter(s -> "roster".equals(s.type())).toList();
		assertThat(rosters).as("rosters to " + playerId).isNotEmpty();
		return rosters.get(rosters.size() - 1).message();
	}

	private void assertLastError(String playerId, String code) {
		Map<String, Object> last = lastTo(playerId);
		assertThat(last.get("type")).as("last message to " + playerId).isEqualTo("error");
		assertThat(last.get("code")).as("error code to " + playerId).isEqualTo(code);
	}

	// "name:score:host:connected" per player, after checking the allow-listed keys.
	@SuppressWarnings("unchecked")
	private static List<String> rosterRows(Map<String, Object> roster) {
		assertThat(roster.keySet()).as("roster keys").containsExactlyInAnyOrder("type", "phase", "players");
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) roster.get("players")) {
			Map<String, Object> p = (Map<String, Object>) o;
			assertThat(p.keySet()).as("roster player keys").containsExactlyInAnyOrder("name", "score", "host",
					"connected");
			assertThat(p.get("name")).isInstanceOf(String.class);
			assertThat(p.get("host")).isInstanceOf(Boolean.class);
			assertThat(p.get("connected")).isInstanceOf(Boolean.class);
			rows.add(p.get("name") + ":" + num(p, "score") + ":" + p.get("host") + ":" + p.get("connected"));
		}
		return rows;
	}

	@SuppressWarnings("unchecked")
	private static List<String> scoreRows(Map<String, Object> scores) {
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) scores.get("scores")) {
			Map<String, Object> r = (Map<String, Object>) o;
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	private Anime currentAnime() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private boolean startedSince(int mark, int index) {
		return outbox.since(mark).stream()
				.anyMatch(s -> "start".equals(s.type()) && num(s.message(), "index") == index);
	}

	// Plays song k: the listed players send ready (a departed player's ready is not forwarded, so the
	// prepare timeout may be needed), then the winner answers with the exact title after the lead,
	// then the reveal pause runs (which sends the next prepare, or gameOver after the last song).
	private void playSong(int k, String winner, String... readyIds) {
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(k);
		int mark = outbox.mark();
		for (String id : readyIds) {
			lobby.ready(id, k);
		}
		if (!startedSince(mark, k)) {
			scheduler.advance(Room.PREPARE_TIMEOUT);
		}
		assertThat(startedSince(mark, k)).as("song %d started", k).isTrue();
		scheduler.advance(Room.LEAD);
		String title = currentAnime().titles().get("en").get(0);
		int beforeChat = outbox.mark();
		lobby.chat(winner, title);
		assertThat(types(outbox.since(beforeChat))).as("song %d answered", k).contains("correct");
		scheduler.advance(Room.REVEAL_PAUSE);
	}

	private void joinThree() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
	}

	// ---- constants ----

	@Test
	void constantsMatchTheSpec() {
		assertThat(Lobby.MAX_PLAYERS).isEqualTo(8);
		assertThat(Lobby.EMPTY_TTL).isEqualTo(Duration.ofMinutes(2));
		assertThat(Lobby.IDLE_TTL).isEqualTo(Duration.ofMinutes(10));
	}

	// ---- join and names ----

	@Test
	void joinSendsJoinedThenRosterToEveryone() {
		build();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.closed()).isFalse();
		assertThat(lobby.host()).isNull();

		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		List<Sent> first = outbox.all();
		assertThat(first).extracting(Sent::to).containsExactly(P1, P1);
		assertThat(types(first)).containsExactly("joined", "roster");
		assertThat(first.get(0).message()).containsEntry("code", CODE).containsEntry("name", "Mina");
		assertThat(first.get(1).message()).containsEntry("phase", "lobby");
		assertThat(rosterRows(first.get(1).message())).containsExactly("Mina:0:true:true");

		int mark = outbox.mark();
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		List<Sent> second = outbox.since(mark);
		assertThat(types(second)).containsExactly("joined", "roster", "roster");
		assertThat(second.get(0).to()).isEqualTo(P2);
		assertThat(second.get(0).message()).containsEntry("code", CODE).containsEntry("name", "Joon");
		assertThat(second.subList(1, 3)).extracting(Sent::to).as("roster in join order").containsExactly(P1, P2);
		assertThat(second.get(1).message()).isEqualTo(second.get(2).message());
		assertThat(rosterRows(second.get(1).message())).containsExactly("Mina:0:true:true", "Joon:0:false:true");
		assertThat(lobby.names()).containsExactly("Mina", "Joon");
	}

	@Test
	void rosterJsonCarriesOnlyTheAllowListedKeysAndNoPlayerIds() {
		build();
		joinThree();
		Map<String, Object> roster = lastRosterTo(P3);
		rosterRows(roster);
		String json = MAPPER.writeValueAsString(roster);
		assertThat(json).doesNotContain("pid-");
		assertThat(MAPPER.readValue(json, Map.class).keySet()).containsExactlyInAnyOrder("type", "phase", "players");
	}

	@Test
	void eightPlayersJoinAndTheNinthGetsRoomFull() {
		build();
		for (int i = 1; i <= 8; i++) {
			assertThat(lobby.join("pid-" + i, "P" + i)).isEqualTo("P" + i);
		}
		assertThat(rosterRows(lastRosterTo("pid-8"))).hasSize(8);
		int mark = outbox.mark();
		assertThat(lobby.join("pid-9", "P9")).isNull();
		List<Sent> after = outbox.since(mark);
		assertThat(after).hasSize(1);
		assertThat(after.get(0).to()).isEqualTo("pid-9");
		assertThat(after.get(0).message()).containsEntry("type", "error").containsEntry("code", "room-full");
		assertThat(lobby.names()).hasSize(8).doesNotContain("P9");
	}

	@Test
	void duplicateNamesGetTheFirstFreeNumber() {
		build();
		assertThat(lobby.join(P1, "Hana")).isEqualTo("Hana");
		assertThat(lobby.join(P2, " Hana ")).isEqualTo("Hana (2)");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana (3)");
		assertThat(lastTo(P3)).containsEntry("type", "roster");
		assertThat(lobby.names()).containsExactly("Hana", "Hana (2)", "Hana (3)");

		lobby.leave(P2);
		assertThat(lobby.names()).containsExactly("Hana", "Hana (3)");
		assertThat(lobby.join(P4, "Hana")).isEqualTo("Hana (2)");
		assertThat(to(P4).get(0).message()).containsEntry("type", "joined").containsEntry("name", "Hana (2)");
		assertThat(lobby.names()).containsExactly("Hana", "Hana (3)", "Hana (2)");
		assertThat(rosterRows(lastRosterTo(P1))).containsExactly("Hana:0:true:true", "Hana (3):0:false:true",
				"Hana (2):0:false:true");
	}

	@Test
	void badNicknamesAreRejected() {
		build();
		String sixteen = "abcdefghijklmnop";
		for (String bad : new String[] {"", "   ", sixteen + "q"}) {
			int mark = outbox.mark();
			assertThat(lobby.join(P2, bad)).as("nickname '%s'", bad).isNull();
			List<Sent> after = outbox.since(mark);
			assertThat(after).hasSize(1);
			assertThat(after.get(0).to()).isEqualTo(P2);
			assertThat(after.get(0).message()).containsEntry("type", "error").containsEntry("code", "bad-nickname");
		}
		assertThat(lobby.names()).isEmpty();
		assertThat(lobby.join(P1, "  " + sixteen + "  ")).isEqualTo(sixteen);
		assertThat(lobby.names()).containsExactly(sixteen);
	}

	// ---- delivery ----

	@Test
	void everyBroadcastIsOneToPlayerPerConnectedPlayerInJoinOrder() {
		build();
		joinThree();
		int mark = outbox.mark();
		lobby.start(P1, 5);
		List<Sent> prepares = outbox.since(mark).stream().filter(s -> "prepare".equals(s.type())).toList();
		assertThat(prepares).extracting(Sent::to).containsExactly(P1, P2, P3);
		assertThat(prepares.get(1).message()).isEqualTo(prepares.get(0).message());
		assertThat(prepares.get(2).message()).isEqualTo(prepares.get(0).message());

		lobby.leave(P2);
		mark = outbox.mark();
		lobby.ready(P1, 0);
		lobby.ready(P3, 0);
		scheduler.advance(Room.PREPARE_TIMEOUT);
		List<Sent> starts = outbox.since(mark).stream().filter(s -> "start".equals(s.type())).toList();
		assertThat(starts).extracting(Sent::to).as("the departed player gets no broadcast").containsExactly(P1, P3);
		assertThat(outbox.all()).noneMatch(Sent::toAll);
	}

	// ---- host ----

	@Test
	void creatorIsHostAndTheNextEarliestJoinedPlayerTakesOver() {
		build();
		joinThree();
		assertThat(lobby.host()).isEqualTo(P1);
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");

		int mark = outbox.mark();
		lobby.leave(P1);
		assertThat(lobby.host()).isEqualTo(P2);
		assertThat(toSince(mark, P1)).as("a player who left gets nothing").isEmpty();
		assertThat(types(toSince(mark, P2))).contains("roster");
		assertThat(types(toSince(mark, P3))).contains("roster");
		assertThat(lastRosterTo(P2)).containsEntry("phase", "lobby");
		assertThat(rosterRows(lastRosterTo(P2))).containsExactly("Joon:0:true:true", "Hana:0:false:true");
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Joon:0:true:true", "Hana:0:false:true");

		lobby.leave(P2);
		assertThat(lobby.host()).isEqualTo(P3);
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Hana:0:true:true");
	}

	// ---- start rights ----

	@Test
	void onlyTheHostStartsWithACountOfFiveToThirty() {
		build();
		joinThree();
		int mark = outbox.mark();
		lobby.start(P2, 5);
		assertLastError(P2, "not-host");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);

		lobby.start(P1, 4);
		assertLastError(P1, "bad-song-count");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		lobby.start(P1, 31);
		assertLastError(P1, "bad-song-count");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(types(outbox.since(mark))).doesNotContain("prepare", "roster");

		lobby.start(P1, 5);
		assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);
		assertThat(num(outbox.last("prepare").message(), "total")).isEqualTo(5);
	}

	@Test
	void startAndJoinDuringAGameGetInGame() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);

		int mark = outbox.mark();
		lobby.start(P1, 5);
		assertLastError(P1, "in-game");
		assertThat(types(outbox.since(mark))).containsExactly("error");

		mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isNull();
		List<Sent> after = outbox.since(mark);
		assertThat(after).hasSize(1);
		assertThat(after.get(0).to()).isEqualTo(P3);
		assertThat(after.get(0).message()).containsEntry("code", "in-game");

		assertThat(lobby.join(P4, "")).as("the nickname is checked before in-game").isNull();
		assertLastError(P4, "bad-nickname");
		assertThat(lobby.names()).containsExactly("Mina", "Joon");
	}

	@Test
	void notEnoughSongsKeepsTheLobbyAndSendsNoRoster() {
		build(new long[] {60000, 60000, 60000});
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		scheduler.advance(Duration.ofMinutes(9));
		int mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(types(outbox.since(mark))).doesNotContain("roster", "prepare");
		assertLastError(P1, "not-enough-songs");

		// The failed start rearmed the idle timer: 10 min from now, not from the joins.
		scheduler.advance(Duration.ofMinutes(2));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Lobby.IDLE_TTL.minusMinutes(2).minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
	}

	// ---- game and return ----

	@Test
	void threePlayerGameReturnsToTheLobbyWithScoresAndCanStartAgain() {
		build();
		joinThree();

		int mark = outbox.mark();
		lobby.start(P1, 5);
		for (String id : List.of(P1, P2, P3)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("start order for " + id).containsExactly("prepare", "roster");
			assertThat(num(sent.get(0).message(), "index")).isEqualTo(0);
			assertThat(sent.get(1).message()).containsEntry("phase", "playing");
			assertThat(rosterRows(sent.get(1).message())).containsExactly("Mina:0:true:true", "Joon:0:false:true",
					"Hana:0:false:true");
		}

		String[] winners = {P1, P2, P1, P2, P1};
		for (int k = 0; k < 4; k++) {
			playSong(k, winners[k], P1, P2, P3);
			assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);
		}
		// Last song, split so the messages of the final reveal pause can be checked on their own.
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(4);
		lobby.ready(P1, 4);
		lobby.ready(P2, 4);
		lobby.ready(P3, 4);
		scheduler.advance(Room.LEAD);
		lobby.chat(P1, currentAnime().titles().get("en").get(0));
		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		for (String id : List.of(P1, P2, P3)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("end order for " + id).containsExactly("gameOver", "roster");
			assertThat(sent.get(1).message()).containsEntry("phase", "lobby");
			assertThat(rosterRows(sent.get(1).message())).containsExactly("Mina:3:true:true", "Joon:2:false:true",
					"Hana:0:false:true");
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");

		mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);
		assertThat(types(toSince(mark, P2))).containsExactly("prepare", "roster");
		assertThat(rosterRows(lastRosterTo(P2))).as("the new game starts from 0").containsExactly(
				"Mina:0:true:true", "Joon:0:false:true", "Hana:0:false:true");
		assertThat(lastRosterTo(P2)).containsEntry("phase", "playing");
		playSong(0, P3, P1, P2, P3);
		assertThat(scoreRows(outbox.last("scores").message())).containsExactly("Mina:0", "Joon:0", "Hana:1");
	}

	// ---- leave mid-game ----

	@Test
	void playerWhoLeavesMidGameGetsNothingShowsDisconnectedAndIsGoneAfterGameOver() {
		build();
		joinThree();
		lobby.start(P1, 5);

		int mark = outbox.mark();
		lobby.leave(P2);
		assertThat(toSince(mark, P2)).isEmpty();
		Map<String, Object> roster = lastRosterTo(P1);
		assertThat(roster).containsEntry("phase", "playing");
		assertThat(rosterRows(roster)).containsExactly("Mina:0:true:true", "Joon:0:false:false",
				"Hana:0:false:true");
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Mina:0:true:true", "Joon:0:false:false",
				"Hana:0:false:true");
		assertThat(lobby.host()).isEqualTo(P1);

		// Messages from the departed player are not forwarded.
		int beforeChat = outbox.mark();
		lobby.chat(P2, "hello");
		assertThat(outbox.since(beforeChat)).isEmpty();

		String[] winners = {P1, P3, P3, P1, P3};
		for (int k = 0; k < 4; k++) {
			playSong(k, winners[k], P1, P2, P3);
		}
		assertThat(num(outbox.last("prepare").message(), "index")).isEqualTo(4);
		lobby.ready(P1, 4);
		lobby.ready(P3, 4);
		scheduler.advance(Room.PREPARE_TIMEOUT);
		scheduler.advance(Room.LEAD);
		lobby.chat(P3, currentAnime().titles().get("en").get(0));
		int beforeEnd = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		for (String id : List.of(P1, P3)) {
			List<Sent> sent = toSince(beforeEnd, id);
			assertThat(types(sent)).as("end order for " + id).containsExactly("gameOver", "roster");
			assertThat(sent.get(1).message()).containsEntry("phase", "lobby");
			assertThat(rosterRows(sent.get(1).message())).containsExactly("Mina:2:true:true", "Hana:3:false:true");
		}
		assertThat(toSince(mark, P2)).as("no toPlayer to the departed player").isEmpty();
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	// ---- cleanup ----

	@Test
	void emptyLobbyClosesAfterTheEmptyTtlAndCallsOnClosedOnce() {
		build();
		lobby.join(P1, "Mina");
		lobby.leave(P1);
		assertThat(lobby.host()).isNull();
		assertThat(lobby.names()).isEmpty();

		scheduler.advance(Lobby.EMPTY_TTL.minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);

		scheduler.advance(Duration.ofHours(1));
		assertThat(closedCalls.get()).as("onClosed runs once").isEqualTo(1);

		int mark = outbox.mark();
		assertThat(lobby.join(P2, "Joon")).isNull();
		List<Sent> after = outbox.since(mark);
		assertThat(after).hasSize(1);
		assertThat(after.get(0).to()).isEqualTo(P2);
		assertThat(after.get(0).message()).containsEntry("type", "error").containsEntry("code", "room-not-found");
		assertThat(lobby.join(P3, "")).as("a closed lobby is checked first of all").isNull();
		assertLastError(P3, "room-not-found");
	}

	@Test
	void joinBeforeTheEmptyTtlCancelsItAndStaleTimersDoNothing() {
		build();
		lobby.join(P1, "Mina");
		lobby.leave(P1);
		scheduler.advance(Duration.ofMinutes(1));
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.host()).isEqualTo(P2);

		// The empty timer (due at 2 min) and the idle timer armed at the leave (due at 10 min) are stale.
		scheduler.advance(Duration.ofMinutes(2));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Lobby.IDLE_TTL.minusMinutes(2).minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).as("idle timer armed by the join").isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		assertLastError(P2, "room-closed");
	}

	@Test
	void staleIdleTimerFromBeforeAJoinDoesNothing() {
		build();
		lobby.join(P1, "Mina");
		scheduler.advance(Duration.ofMinutes(9));
		lobby.join(P2, "Joon");
		scheduler.advance(Duration.ofMinutes(1));
		assertThat(lobby.closed()).as("the idle timer from the first join is stale").isFalse();
		scheduler.advance(Lobby.IDLE_TTL.minusMinutes(1).minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
	}

	@Test
	void idleTtlClosesALobbyWithPlayersWhoGetRoomClosed() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		scheduler.advance(Lobby.IDLE_TTL.minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		int mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		List<Sent> closing = outbox.since(mark);
		assertThat(closing).extracting(Sent::to).containsExactly(P1, P2);
		assertThat(closing).allSatisfy(s -> assertThat(s.message()).containsEntry("type", "error")
				.containsEntry("code", "room-closed"));

		mark = outbox.mark();
		lobby.start(P1, 5);
		lobby.ready(P1, 0);
		lobby.chat(P1, "hello");
		lobby.leave(P2);
		scheduler.advance(Duration.ofHours(1));
		assertThat(outbox.since(mark)).as("every method is a no-op after closing").isEmpty();
		assertThat(closedCalls.get()).isEqualTo(1);

		assertThat(lobby.join(P3, "Hana")).isNull();
		assertLastError(P3, "room-not-found");
	}

	@Test
	void idleTimerDoesNotCloseDuringAGameAndIsRearmedOnTheReturn() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		scheduler.advance(Duration.ofMinutes(9).plusSeconds(50));
		lobby.start(P1, 5);
		// Idle would be due at 10 min; stay in the prepare of song 0 past it.
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(lobby.closed()).isFalse();
		assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.ofType("error")).isEmpty();

		String[] winners = {P1, P2, P1, P2, P1};
		for (int k = 0; k < 5; k++) {
			playSong(k, winners[k], P1, P2);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lastRosterTo(P1)).containsEntry("phase", "lobby");
		assertThat(lobby.closed()).isFalse();

		scheduler.advance(Lobby.IDLE_TTL.minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
		assertLastError(P1, "room-closed");
		assertLastError(P2, "room-closed");
	}

	@Test
	void closingDuringAGameDropsTheRoomAndNothingMoreIsDelivered() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		lobby.start(P1, 5);
		lobby.leave(P1);
		lobby.leave(P2);
		assertThat(lobby.host()).isNull();

		int mark = outbox.mark();
		scheduler.advance(Lobby.EMPTY_TTL.minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);

		// The dropped Room's timers keep running (they cannot be cancelled), but are muted.
		scheduler.advance(Duration.ofHours(1));
		assertThat(outbox.since(mark)).as("no message after the close").isEmpty();
		assertThat(closedCalls.get()).isEqualTo(1);

		assertThat(lobby.join(P3, "Hana")).isNull();
		assertLastError(P3, "room-not-found");
	}

}
