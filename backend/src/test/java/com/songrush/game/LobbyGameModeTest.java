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
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 101 (D-112) at Lobby level, on the cycle 036 fakes: Lobby.start(playerId, songCount, filter,
// answerMode, gameMode) (docs/cycles/101-game-modes.md items 1 and 6, R1, R2, R5, R6). The game mode
// travels like answerMode (cycle 100 A8): only the host's start sets it, for that game only; every
// member gets settings{gameMode:"ranked"} before the first prepare, and every joined + snapshot during
// a ranked game is followed by that settings. In ranked mode the snapshot's song carries answered and
// correct [{name, rank}]. A leave or disconnect re-checks "every connected Room player answered", but
// never ends a song with nobody connected (the Lobby pauses instead, cycle 044). Other API as in
// RoomRankedTest's header. As in LobbyTest, every message is a toPlayer.
class LobbyGameModeTest {

	private static final String CODE = "RANKD";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private final AtomicInteger closedCalls = new AtomicInteger();

	private Built built;

	private Lobby lobby;

	private void build() {
		built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, 20));
		lobby = new Lobby(CODE, built.catalog(), tokens, scheduler, clock, new Random(100), outbox,
				closedCalls::incrementAndGet, built.covers());
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	private static Map<String, Object> msg(String type, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		m.putAll(row(keysAndValues));
		return m;
	}

	private static Map<String, Object> row(Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	private static final Map<String, Object> RANKED_SETTINGS = msg("settings", "gameMode", "ranked");

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private List<Sent> toSince(int mark, String playerId) {
		return outbox.since(mark).stream().filter(s -> playerId.equals(s.to())).toList();
	}

	private List<Sent> ofTypeSince(int mark, String type) {
		return outbox.since(mark).stream().filter(s -> type.equals(s.type())).toList();
	}

	private void joinTwo() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
	}

	private void startRanked() {
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	private Map<String, Object> prepareOf(String playerId) {
		List<Sent> prepares = outbox.all().stream().filter(s -> playerId.equals(s.to()) && "prepare".equals(s.type()))
				.toList();
		assertThat(prepares).as("prepares to " + playerId).isNotEmpty();
		return prepares.get(prepares.size() - 1).message();
	}

	private String title() {
		String url = (String) prepareOf(P1).get("audioUrl");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return ChoiceFixture.title(built.animeIndexOfTheme(themeId));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> snapshotSong(List<Sent> sent) {
		Sent snapshot = sent.stream().filter(s -> "snapshot".equals(s.type())).findFirst().orElseThrow();
		return (Map<String, Object>) snapshot.message().get("song");
	}

	private void toAudible(int index, String... ids) {
		for (String id : ids) {
			lobby.ready(id, index);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
	}

	private static Map<String, Object> ranked(String name, int rank, int points) {
		return row("name", name, "rank", rank, "points", points);
	}

	// ---- start ----

	@Test
	void theHostsRankedStartGivesEveryMemberSettingsThenThePrepare() {
		build();
		joinTwo();
		int mark = outbox.mark();
		startRanked();
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("to " + id).containsExactly("settings", "prepare", "roster");
			assertThat(sent.get(0).message()).isEqualTo(RANKED_SETTINGS);
			assertThat(sent.get(1).message()).doesNotContainKey("choices");
		}
	}

	@Test
	void aFilterTheAnswerModeAndTheGameModeGoInOneSettings() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, new DrawFilter(Set.of("OP"), null, null, DrawFilter.EASY), AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).containsExactly("settings", "prepare", "roster");
			assertThat(sent.get(0).message()).isEqualTo(
					msg("settings", "types", List.of("OP"), "tier", "easy", "answerMode", "choice", "gameMode", "ranked"));
		}
	}

	@Test
	void anExplicitFirstStartIsTodaysGame() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		for (String id : List.of(P1, P2)) {
			assertThat(types(toSince(mark, id))).containsExactly("prepare", "roster");
		}
		String title = title();
		toAudible(0, P1, P2);
		mark = outbox.mark();
		lobby.chat(P2, title);
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).startsWith("correct", "chat", "reveal", "scores");
			assertThat(sent.get(0).message()).isEqualTo(msg("correct", "index", 0, "name", "Joon"));
			assertThat(sent.get(2).message()).doesNotContainKey("ranking");
		}
	}

	@Test
	void aNonHostCannotStartARankedGame() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P2, 5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P2);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(msg("error", "code", "not-host"));
	}

	@Test
	void theModeCannotChangeDuringAGameAndHoldsOnlyForItsGame() {
		build();
		joinTwo();
		startRanked();
		int mark = outbox.mark();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(msg("error", "code", "in-game"));
		for (int k = 0; k < 5; k++) {
			lobby.ready(P1, k);
			lobby.ready(P2, k);
			lobby.skip(P1, k);
			lobby.skip(P2, k);
			assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);

		mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(ofTypeSince(mark, "settings")).isEmpty();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");
		assertThat(snapshotSong(toSince(mark, P3))).doesNotContainKeys("answered", "correct");
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P1, title);
		assertThat(outbox.last("correct").message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- ranked play through the lobby ----

	@Test
	void aCorrectChatGoesToEveryoneAsCorrectAndItsTextToTheSenderOnly() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		int mark = outbox.mark();
		lobby.chat(P2, title);
		assertThat(toSince(mark, P1)).extracting(Sent::message).containsExactly(msg("correct", "index", 0, "name", "Joon",
				"rank", 1));
		assertThat(toSince(mark, P2)).extracting(Sent::message).containsExactly(
				msg("correct", "index", 0, "name", "Joon", "rank", 1), msg("chat", "name", "Joon", "text", title));
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		lobby.chat(P1, title);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		Map<String, Object> reveal = outbox.last("reveal").message();
		assertThat(reveal.get("winner")).isEqualTo("Joon");
		assertThat(reveal.get("ranking")).isEqualTo(List.of(ranked("Joon", 1, 3), ranked("Mina", 2, 2)));
	}

	@Test
	void aRejoinSnapshotCarriesAnsweredAndTheCorrectNamesThenSettings() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P2, title);
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(RANKED_SETTINGS);
		List<Map<String, Object>> names = List.of(row("name", "Joon", "rank", 1));
		assertThat(snapshotSong(sent)).containsEntry("answered", true).containsEntry("correct", names);

		// A replaced socket of the player who has not answered.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		sent = toSince(mark, P1);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings");
		assertThat(snapshotSong(sent)).containsEntry("answered", false).containsEntry("correct", names);

		// The rejoined player is still answered: the title is just a chat line now.
		mark = outbox.mark();
		lobby.chat(P2, title);
		assertThat(ofTypeSince(mark, "correct")).isEmpty();
		assertThat(ofTypeSince(mark, "chat")).extracting(Sent::to).containsExactlyInAnyOrder(P1, P2);
	}

	@Test
	void aLateJoinerGetsTheSettingsAndIsNotWaitedFor() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(RANKED_SETTINGS);
		assertThat(snapshotSong(sent)).containsEntry("answered", false).containsEntry("correct", List.of());
		lobby.chat(P1, title);
		lobby.chat(P2, title);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void theLastUnansweredMemberLeavingEndsTheSong() {
		build();
		joinTwo();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		String title = title();
		toAudible(0, P1, P2, P3);
		lobby.chat(P3, title);
		lobby.chat(P1, title);
		int mark = outbox.mark();
		lobby.leave(P2);
		List<Sent> reveals = ofTypeSince(mark, "reveal");
		assertThat(reveals).extracting(Sent::to).containsExactlyInAnyOrder(P1, P3);
		assertThat(reveals.get(0).message().get("winner")).isEqualTo("Hana");
		assertThat(reveals.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Hana", 1, 3), ranked("Mina", 2, 2)));
	}

	@Test
	void theLastUnansweredMemberDroppingEndsTheSong() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P1, title);
		int mark = outbox.mark();
		lobby.disconnect(P2);
		assertThat(ofTypeSince(mark, "reveal")).extracting(Sent::to).containsExactly(P1);
		assertThat(outbox.last("reveal").message().get("winner")).isEqualTo("Mina");
	}

	// Added acceptance (R1): A answered, then disconnects; B unanswered, then disconnects: no reveal,
	// and B's return replays the same song.
	@Test
	void answeredThenUnansweredDisconnectSendsNoRevealAndTheReturnReplaysTheSong() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P1, title);
		int mark = outbox.mark();
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		assertThat(ofTypeSince(mark, "reveal")).isEmpty();

		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(ofTypeSince(mark, "reveal")).isEmpty();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		List<Sent> prepares = ofTypeSince(mark, "prepare");
		assertThat(prepares).extracting(Sent::to).containsExactly(P2);
		assertThat(prepares.get(0).message().get("index")).isEqualTo(0);
		String url = (String) prepares.get(0).message().get("audioUrl");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		assertThat(ChoiceFixture.title(built.animeIndexOfTheme(themeId))).as("the same song").isEqualTo(title);

		toAudible(0, P2);
		mark = outbox.mark();
		lobby.chat(P2, title);
		assertThat(toSince(mark, P2)).extracting(Sent::type).startsWith("correct", "chat", "reveal", "scores");
		assertThat(toSince(mark, P2).get(0).message()).isEqualTo(msg("correct", "index", 0, "name", "Joon", "rank", 2));
		Map<String, Object> reveal = toSince(mark, P2).get(2).message();
		assertThat(reveal.get("winner")).isEqualTo("Mina");
		assertThat(reveal.get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Joon", 2, 2)));
	}

	// Added acceptance (R2): A answers, everyone leaves, both return; A's repeated correct chat after
	// the new startAt earns nothing.
	@Test
	void aRepeatedCorrectChatAfterEveryoneLeftAndReturnedEarnsNothing() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P1, title);
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.reconnect(P2)).isTrue();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		toAudible(0, P1, P2);
		int mark = outbox.mark();
		lobby.chat(P1, title);
		assertThat(ofTypeSince(mark, "correct")).isEmpty();
		assertThat(ofTypeSince(mark, "chat")).extracting(Sent::to).containsExactlyInAnyOrder(P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		lobby.chat(P2, title);
		assertThat(outbox.last("correct").message()).isEqualTo(msg("correct", "index", 0, "name", "Joon", "rank", 2));
		assertThat(outbox.last("scores").message()).isEqualTo(msg("scores", "scores",
				List.of(row("name", "Mina", "score", 3), row("name", "Joon", "score", 2))));
	}

	// R6: the answered player's lone return ends the song when it starts.
	@Test
	void anAnsweredPlayersLoneReturnEndsTheSongWhenItStarts() {
		build();
		joinTwo();
		startRanked();
		String title = title();
		toAudible(0, P1, P2);
		lobby.chat(P1, title);
		lobby.disconnect(P1);
		lobby.disconnect(P2);
		assertThat(lobby.reconnect(P1)).isTrue();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		int mark = outbox.mark();
		lobby.ready(P1, 0);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		List<Sent> reveals = ofTypeSince(mark, "reveal");
		assertThat(reveals).extracting(Sent::to).containsExactly(P1);
		assertThat(reveals.get(0).message().get("winner")).isEqualTo("Mina");
		assertThat(reveals.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3)));
	}

}
