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

// Cycle 042 (B-005b) criterion 1: Lobby.disconnect, Lobby.reconnect and the 30 s grace period on the
// cycle 036 fakes (fake clock, manual scheduler, seeded Random, recording Outbox, real AudioTokens).
// As in LobbyTest, every message must reach the outbox as a toPlayer (docs/cycles/042-reconnect.md).
class LobbyReconnectTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

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

	private void build() {
		catalog = GameFixture.build(dir, SEVEN, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(42), outbox,
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

	private Map<String, Object> lastRosterTo(String playerId) {
		List<Sent> rosters = outbox.all().stream()
				.filter(s -> playerId.equals(s.to()) && "roster".equals(s.type()))
				.toList();
		assertThat(rosters).as("rosters to " + playerId).isNotEmpty();
		return rosters.get(rosters.size() - 1).message();
	}

	// "name:score:host:connected" per player.
	@SuppressWarnings("unchecked")
	private static List<String> rows(Object players) {
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) players) {
			Map<String, Object> p = (Map<String, Object>) o;
			assertThat(p.keySet()).as("player row keys").containsExactlyInAnyOrder("name", "score", "host",
					"connected");
			rows.add(p.get("name") + ":" + num(p, "score") + ":" + p.get("host") + ":" + p.get("connected"));
		}
		return rows;
	}

	private static List<String> rosterRows(Map<String, Object> roster) {
		assertThat(roster.keySet()).as("roster keys").containsExactlyInAnyOrder("type", "phase", "players");
		return rows(roster.get("players"));
	}

	// "name:score" per row of the snapshot's scores.
	@SuppressWarnings("unchecked")
	private static List<String> scoreRows(Object scores) {
		assertThat(scores).as("scores").isInstanceOf(List.class);
		List<String> rows = new ArrayList<>();
		for (Object o : (List<?>) scores) {
			Map<String, Object> r = (Map<String, Object>) o;
			assertThat(r.keySet()).as("score row keys").containsExactlyInAnyOrder("name", "score");
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// The snapshot with exactly the specified keys, after checking it never carries a player id.
	private static Map<String, Object> assertSnapshot(Map<String, Object> snapshot) {
		assertThat(snapshot.get("type")).isEqualTo("snapshot");
		assertThat(snapshot.keySet()).as("snapshot keys").containsExactlyInAnyOrder("type", "phase", "players",
				"song", "scores");
		assertThat(MAPPER.writeValueAsString(snapshot)).as("no player id").doesNotContain("pid-");
		return snapshot;
	}

	// Asserts the player got exactly joined{code, name} then snapshot first after the mark, and
	// returns the snapshot.
	private Map<String, Object> assertJoinedThenSnapshot(int mark, String playerId, String name) {
		List<Sent> sent = toSince(mark, playerId);
		assertThat(sent.size()).as("messages to " + playerId).isGreaterThanOrEqualTo(2);
		assertThat(types(sent.subList(0, 2))).containsExactly("joined", "snapshot");
		Map<String, Object> joined = sent.get(0).message();
		assertThat(joined.keySet()).containsExactlyInAnyOrder("type", "code", "name");
		assertThat(joined).containsEntry("code", CODE).containsEntry("name", name);
		return assertSnapshot(sent.get(1).message());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> song(Map<String, Object> snapshot) {
		Object song = snapshot.get("song");
		assertThat(song).as("snapshot song").isInstanceOf(Map.class);
		return (Map<String, Object>) song;
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

	private void joinThree() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
	}

	private void readyAll(int index) {
		for (String id : List.of(P1, P2, P3)) {
			lobby.ready(id, index);
		}
	}

	// From PLAYING: waits for startAt and lets the winner answer with the exact title.
	private void answer(String winner) {
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		long left = num(outbox.last("start").message(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		lobby.chat(winner, currentAnime().titles().get("en").get(0));
		assertThat(types(outbox.since(mark))).as("answered").contains("correct");
	}

	// Plays every remaining song up to (not including) the final reveal pause: each song gets the
	// ready messages (or the prepare timeout), then the winner answers.
	private void playToLastReveal(String winner) {
		while (true) {
			if (lobby.phase() == Room.Phase.REVEAL) {
				scheduler.advance(Room.REVEAL_PAUSE);
				continue;
			}
			if (lobby.phase() == Room.Phase.PREPARE) {
				int index = (int) num(outbox.last("prepare").message(), "index");
				readyAll(index);
				if (lobby.phase() == Room.Phase.PREPARE) {
					scheduler.advance(Room.PREPARE_TIMEOUT);
				}
			}
			answer(winner);
			Map<String, Object> prepare = outbox.last("prepare").message();
			if (num(prepare, "index") == num(prepare, "total") - 1) {
				assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
				return;
			}
		}
	}

	// ---- constants ----

	@Test
	void graceIsThirtySeconds() {
		assertThat(Lobby.GRACE).isEqualTo(Duration.ofSeconds(30));
	}

	// ---- disconnect and reconnect in the lobby ----

	@Test
	void disconnectInTheLobbyShowsDisconnectedAndMovesTheHost() {
		build();
		joinThree();
		assertThat(lobby.host()).isEqualTo(P1);

		int mark = outbox.mark();
		lobby.disconnect(P1);
		assertThat(lobby.host()).as("the host rule applies at once").isEqualTo(P2);
		for (String id : List.of(P2, P3)) {
			assertThat(types(toSince(mark, id))).as("to " + id).containsExactly("roster");
			Map<String, Object> roster = toSince(mark, id).get(0).message();
			assertThat(roster).containsEntry("phase", "lobby");
			assertThat(rosterRows(roster)).containsExactly("Mina:0:false:false", "Joon:0:true:true",
					"Hana:0:false:true");
		}
		assertThat(lobby.names()).as("the member keeps the seat during grace").containsExactly("Mina", "Joon",
				"Hana");
	}

	@Test
	void reconnectWithinGraceSendsJoinedThenALobbySnapshotThenRosterToEveryone() {
		build();
		joinThree();
		lobby.disconnect(P2);
		scheduler.advance(Duration.ofSeconds(10));

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P2, "Joon");
		assertThat(snapshot).containsEntry("phase", "lobby");
		assertThat(snapshot).containsKey("song");
		assertThat(snapshot.get("song")).isNull();
		assertThat(snapshot.get("scores")).isEqualTo(List.of());
		assertThat(rows(snapshot.get("players"))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");

		for (String id : List.of(P1, P2, P3)) {
			List<Sent> rosters = toSince(mark, id).stream().filter(s -> "roster".equals(s.type())).toList();
			assertThat(rosters).as("roster to " + id).hasSize(1);
			assertThat(rosters.get(0).message()).containsEntry("phase", "lobby");
			assertThat(rosterRows(rosters.get(0).message())).containsExactly("Mina:0:true:true",
					"Joon:0:false:true", "Hana:0:false:true");
		}
		List<Sent> toP2 = toSince(mark, P2);
		assertThat(types(toP2)).containsExactly("joined", "snapshot", "roster");
		assertThat(toSince(mark, P1).size()).isEqualTo(1);
	}

	@Test
	void reconnectingHostGetsTheHostBack() {
		build();
		joinThree();
		lobby.disconnect(P1);
		assertThat(lobby.host()).isEqualTo(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.host()).as("earliest-joined connected player").isEqualTo(P1);
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P1, "Mina");
		assertThat(rows(snapshot.get("players"))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
	}

	@Test
	void reconnectJustBeforeTheGraceEndsStillWorks() {
		build();
		joinThree();
		lobby.disconnect(P3);
		scheduler.advance(Lobby.GRACE.minusMillis(1));
		assertThat(lobby.reconnect(P3)).isTrue();
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");
		// The cancelled grace timer does nothing later.
		scheduler.advance(Duration.ofSeconds(5));
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");
		assertThat(rosterRows(lastRosterTo(P1))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
	}

	// ---- grace expiry ----

	@Test
	void afterTheGraceInTheLobbyReconnectFailsSilentlyAndThePlayerIsGone() {
		build();
		joinThree();
		lobby.disconnect(P2);
		scheduler.advance(Lobby.GRACE);
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
		assertThat(rosterRows(lastRosterTo(P1))).containsExactly("Mina:0:true:true", "Hana:0:false:true");

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(outbox.since(mark)).as("nothing is sent").isEmpty();
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
	}

	// ---- during a game ----

	@Test
	void reconnectInPrepareGetsAPlayingSnapshotWithTheSongOfThatPrepare() {
		build();
		joinThree();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> prepare = outbox.last("prepare").message();

		lobby.disconnect(P2);
		assertThat(rosterRows(lastRosterTo(P1))).containsExactly("Mina:0:true:true", "Joon:0:false:false",
				"Hana:0:false:true");
		assertThat(lastRosterTo(P1)).containsEntry("phase", "playing");
		scheduler.advance(Duration.ofSeconds(10));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P2, "Joon");
		assertThat(snapshot).containsEntry("phase", "playing");
		Map<String, Object> song = song(snapshot);
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertThat(num(song, "index")).isEqualTo(num(prepare, "index"));
		assertThat(num(song, "total")).isEqualTo(5);
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(scoreRows(snapshot.get("scores"))).containsExactly("Mina:0", "Joon:0", "Hana:0");
		assertThat(rows(snapshot.get("players"))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
		Map<String, Object> roster = lastRosterTo(P3);
		assertThat(roster).containsEntry("phase", "playing");
		assertThat(rosterRows(roster)).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");

		// The returning player is a player again: ready is forwarded and the song starts.
		readyAll(0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void reconnectInPlayingAddsStartAndDeadlineAndCurrentScores() {
		build();
		joinThree();
		lobby.start(P1, 5);
		readyAll(0);
		answer(P1);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(num(prepare, "index")).isEqualTo(1);
		readyAll(1);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		Map<String, Object> start = outbox.last("start").message();

		lobby.disconnect(P3);
		scheduler.advance(Duration.ofSeconds(5));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing");
		Map<String, Object> song = song(snapshot);
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt");
		assertThat(num(song, "index")).isEqualTo(1);
		assertThat(num(song, "total")).isEqualTo(5);
		assertThat(song.get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(num(song, "startAt")).isEqualTo(num(start, "startAt"));
		assertThat(num(song, "deadlineAt")).isEqualTo(num(start, "deadlineAt"));
		assertThat(scoreRows(snapshot.get("scores"))).containsExactly("Mina:1", "Joon:0", "Hana:0");
		assertThat(rows(snapshot.get("players"))).containsExactly("Mina:1:true:true", "Joon:0:false:true",
				"Hana:0:false:true");

		// The returning player can score.
		answer(P3);
		assertThat(outbox.last("correct").message()).containsEntry("name", "Hana");
	}

	@Test
	void reconnectInRevealGetsNoSong() {
		build();
		joinThree();
		lobby.start(P1, 5);
		readyAll(0);
		answer(P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		lobby.disconnect(P3);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing").containsKey("song");
		assertThat(snapshot.get("song")).isNull();
		assertThat(scoreRows(snapshot.get("scores"))).containsExactly("Mina:0", "Joon:1", "Hana:0");

		// The next prepare reaches the returning player.
		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(types(toSince(mark, P3))).contains("prepare");
	}

	@Test
	void hostDisconnectingInAGameMovesTheHostAtOnce() {
		build();
		joinThree();
		lobby.start(P1, 5);
		lobby.disconnect(P1);
		assertThat(lobby.host()).isEqualTo(P2);
		assertThat(rosterRows(lastRosterTo(P2))).containsExactly("Mina:0:false:false", "Joon:0:true:true",
				"Hana:0:false:true");
	}

	@Test
	void afterTheGraceInAGameThePlayerStaysDisconnectedUntilGameOverAndIsThenRemoved() {
		build();
		joinThree();
		lobby.start(P1, 5);
		lobby.disconnect(P2);
		scheduler.advance(Lobby.GRACE);

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(outbox.since(mark)).as("nothing is sent").isEmpty();
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");

		playToLastReveal(P1);
		Map<String, Object> roster = lastRosterTo(P1);
		assertThat(roster).containsEntry("phase", "playing");
		assertThat(rosterRows(roster)).anyMatch(r -> r.startsWith("Joon:") && r.endsWith(":false"));
		assertThat(lobby.reconnect(P2)).isFalse();

		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(types(toSince(mark, P1))).containsExactly("gameOver", "roster");
		assertThat(toSince(mark, P2)).isEmpty();
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
		Map<String, Object> after = lastRosterTo(P1);
		assertThat(after).containsEntry("phase", "lobby");
		assertThat(rosterRows(after)).containsExactly("Mina:5:true:true", "Hana:0:false:true");
		assertThat(lobby.reconnect(P2)).isFalse();
	}

	@Test
	void memberInGraceAtStartSpectatesAndCannotScore() {
		build();
		joinThree();
		lobby.disconnect(P3);
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);

		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P3, "Hana");
		assertThat(snapshot).containsEntry("phase", "playing");
		assertThat(song(snapshot).keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertThat(scoreRows(snapshot.get("scores"))).as("scores come from the Room's players")
				.containsExactly("Mina:0", "Joon:0");

		lobby.ready(P1, 0);
		lobby.ready(P2, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
		mark = outbox.mark();
		lobby.chat(P3, currentAnime().titles().get("en").get(0));
		assertThat(outbox.since(mark)).as("the Room ignores a non-player's chat").isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- empty lobby ----

	@Test
	void reconnectWithinGraceCancelsThePendingEmptyTtl() {
		build();
		lobby.join(P1, "Mina");
		lobby.disconnect(P1);
		assertThat(lobby.host()).isNull();
		scheduler.advance(Duration.ofSeconds(20));
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.host()).isEqualTo(P1);

		scheduler.advance(Lobby.EMPTY_TTL);
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();
		scheduler.advance(Duration.ofMinutes(1));
		assertThat(lobby.closed()).isFalse();
		assertThat(lobby.names()).containsExactly("Mina");
	}

	@Test
	void lastDisconnectClosesTheLobbyAtExactlyTheEmptyTtlDespiteTheLaterGraceExpiry() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		lobby.disconnect(P1);
		scheduler.advance(Duration.ofSeconds(10));
		// The last connected player leaves at T.
		lobby.disconnect(P2);

		scheduler.advance(Lobby.GRACE);
		assertThat(lobby.closed()).as("grace expiry does not close the lobby").isFalse();
		scheduler.advance(Lobby.EMPTY_TTL.minus(Lobby.GRACE).minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		assertThat(closedCalls.get()).isZero();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).as("closed at T + EMPTY_TTL").isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);

		scheduler.advance(Duration.ofHours(1));
		assertThat(closedCalls.get()).isEqualTo(1);
		assertThat(lobby.reconnect(P2)).isFalse();
	}

	@Test
	void lastDisconnectDuringAGameClosesAtTheEmptyTtl() {
		build();
		lobby.join(P1, "Mina");
		lobby.join(P2, "Joon");
		lobby.start(P1, 5);
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		scheduler.advance(Lobby.EMPTY_TTL.minusMillis(1));
		assertThat(lobby.closed()).isFalse();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(lobby.closed()).isTrue();
		assertThat(closedCalls.get()).isEqualTo(1);
	}

	// ---- no leaks ----

	@Test
	void snapshotAndRosterCarryNoPlayerIdAndNothingOfTheCurrentSong() {
		build();
		joinThree();
		lobby.start(P1, 5);
		readyAll(0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();

		Theme theme = currentTheme();
		Anime anime = currentAnime();
		List<String> secrets = new ArrayList<>();
		anime.titles().values().forEach(secrets::addAll);
		secrets.add(anime.reveal());
		secrets.add(theme.song());
		secrets.addAll(theme.artists());
		secrets.add(GameFixture.basename(theme.id() - GameFixture.THEME_BASE));
		assertThat(secrets).allMatch(s -> s != null && !s.isEmpty());

		List<Sent> checked = outbox.since(mark).stream()
				.filter(s -> "snapshot".equals(s.type()) || "roster".equals(s.type()))
				.toList();
		assertThat(types(checked)).contains("snapshot", "roster");
		for (Sent s : checked) {
			String json = MAPPER.writeValueAsString(s.message());
			assertThat(json).as(s.type()).doesNotContain("pid-").doesNotContain("SkyGarden")
					.doesNotContain("anilistId").doesNotContain("themeId").doesNotContain("animeId");
			for (String secret : secrets) {
				assertThat(json).as(s.type() + " leaks " + secret).doesNotContain(secret);
			}
		}
		assertSnapshot(outbox.last("snapshot").message());
	}

	// ---- replacement contract ----

	@Test
	void reconnectOfAConnectedMemberSendsOnlyJoinedAndSnapshotToThatPlayer() {
		build();
		joinThree();
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P2, P2);
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P2, "Joon");
		assertThat(snapshot).containsEntry("phase", "lobby");
		assertThat(snapshot.get("song")).isNull();
		assertThat(snapshot.get("scores")).isEqualTo(List.of());
		assertThat(rows(snapshot.get("players"))).isEqualTo(rosterRows(lastRosterTo(P1)));
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");
		assertThat(lobby.host()).isEqualTo(P1);
	}

	@Test
	void reconnectOfAConnectedMemberDuringAGameSendsNoRoster() {
		build();
		joinThree();
		lobby.start(P1, 5);
		Map<String, Object> prepare = outbox.last("prepare").message();
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P1, P1);
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P1, "Mina");
		assertThat(snapshot).containsEntry("phase", "playing");
		assertThat(song(snapshot).get("audioUrl")).isEqualTo(prepare.get("audioUrl"));
		assertThat(scoreRows(snapshot.get("scores"))).containsExactly("Mina:0", "Joon:0", "Hana:0");
	}

	// ---- order and left members ----

	@Test
	void disconnectThenReconnectAtTheSameInstantWorks() {
		build();
		joinThree();
		lobby.disconnect(P3);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P3)).isTrue();
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");
		assertThat(rosterRows(lastRosterTo(P1))).containsExactly("Mina:0:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
		scheduler.advance(Lobby.GRACE.plusSeconds(1));
		assertThat(lobby.names()).as("the cancelled grace timer does nothing").containsExactly("Mina", "Joon",
				"Hana");
	}

	@Test
	void leaveDuringAGameThenReconnectFails() {
		build();
		joinThree();
		lobby.start(P1, 5);
		lobby.leave(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void leaveDuringGraceThenReconnectFails() {
		build();
		joinThree();
		lobby.start(P1, 5);
		lobby.disconnect(P2);
		lobby.leave(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void leaveInTheLobbyOrAnUnknownIdThenReconnectFails() {
		build();
		joinThree();
		lobby.leave(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(lobby.reconnect("pid-unknown")).isFalse();
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
	}

	// ---- grace and gameOver ----

	@Test
	void memberDisconnectedTwoSecondsBeforeGameOverStaysAndCanReconnectUntilTheGraceEnds() {
		build();
		joinThree();
		lobby.start(P1, 5);
		playToLastReveal(P1);
		scheduler.advance(Room.REVEAL_PAUSE.minusSeconds(2));
		lobby.disconnect(P2);
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.names()).as("still in grace at the return").containsExactly("Mina", "Joon", "Hana");
		Map<String, Object> roster = lastRosterTo(P1);
		assertThat(roster).containsEntry("phase", "lobby");
		assertThat(rosterRows(roster)).containsExactly("Mina:5:true:true", "Joon:0:false:false",
				"Hana:0:false:true");

		scheduler.advance(Lobby.GRACE.minusSeconds(2).minusMillis(1));
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		Map<String, Object> snapshot = assertJoinedThenSnapshot(mark, P2, "Joon");
		assertThat(snapshot).containsEntry("phase", "lobby");
		assertThat(snapshot.get("song")).isNull();
		assertThat(snapshot.get("scores")).isEqualTo(List.of());
		assertThat(rosterRows(lastRosterTo(P3))).containsExactly("Mina:5:true:true", "Joon:0:false:true",
				"Hana:0:false:true");
	}

	@Test
	void memberInGraceAtGameOverIsRemovedWhenTheGraceRunsOut() {
		build();
		joinThree();
		lobby.start(P1, 5);
		playToLastReveal(P1);
		scheduler.advance(Room.REVEAL_PAUSE.minusSeconds(2));
		lobby.disconnect(P2);
		scheduler.advance(Duration.ofSeconds(2));
		assertThat(lobby.names()).containsExactly("Mina", "Joon", "Hana");

		scheduler.advance(Lobby.GRACE.minusSeconds(2));
		assertThat(lobby.names()).containsExactly("Mina", "Hana");
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isFalse();
		assertThat(outbox.since(mark)).isEmpty();
	}

}
