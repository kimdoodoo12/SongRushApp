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
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 100 (D-111) at Lobby level, on the cycle 036 fakes: Lobby.start(playerId, songCount,
// filter, AnswerMode) and Lobby.pick(playerId, index, choice) (docs/cycles/100-choice-answers.md
// item 1 with A8, item 4 with A3 and A11). The mode travels like a draw filter: only the host's start
// sets it, for that game only, and every member gets settings{answerMode:"choice"} before the first
// prepare; every joined + snapshot during a choice game is followed by that settings. The snapshot's
// song carries the choices and that player's picked. As in LobbyTest, every message is a toPlayer.
class LobbyAnswerModeTest {

	private static final String CODE = "CHOIC";

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
		build(ChoiceFixture.coveredSingles(0, 20));
	}

	private void build(List<ChoiceFixture.Spec> specs) {
		built = ChoiceFixture.build(dir, specs);
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
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	private static final Map<String, Object> CHOICE_SETTINGS = msg("settings", "answerMode", "choice");

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

	private void startChoice() {
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// The last prepare sent to the player.
	private Map<String, Object> prepareOf(String playerId) {
		List<Sent> prepares = outbox.all().stream().filter(s -> playerId.equals(s.to()) && "prepare".equals(s.type()))
				.toList();
		assertThat(prepares).as("prepares to " + playerId).isNotEmpty();
		return prepares.get(prepares.size() - 1).message();
	}

	@SuppressWarnings("unchecked")
	private static List<String> coverUrls(Map<String, Object> carrier) {
		assertThat(carrier).containsKey("choices");
		List<String> urls = new ArrayList<>();
		for (Object choice : (List<Object>) carrier.get("choices")) {
			urls.add((String) ((Map<String, Object>) choice).get("coverUrl"));
		}
		return urls;
	}

	private int correctPosition() {
		Map<String, Object> prepare = prepareOf(P1);
		String url = (String) prepare.get("audioUrl");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		int pos = coverUrls(prepare).indexOf(ChoiceFixture.coverUrl(built.animeIndexOfTheme(themeId)));
		assertThat(pos).isGreaterThanOrEqualTo(0);
		return pos;
	}

	private int wrongPosition() {
		return correctPosition() == 0 ? 1 : 0;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> snapshotSong(List<Sent> sent) {
		Sent snapshot = sent.stream().filter(s -> "snapshot".equals(s.type())).findFirst().orElseThrow();
		return (Map<String, Object>) snapshot.message().get("song");
	}

	private void toAudible(int index) {
		lobby.ready(P1, index);
		lobby.ready(P2, index);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
	}

	private void skipSong(int index) {
		lobby.ready(P1, index);
		lobby.ready(P2, index);
		lobby.skip(P1, index);
		lobby.skip(P2, index);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
	}

	// ---- start ----

	@Test
	void theHostsChoiceStartGivesEveryMemberSettingsThenAPrepareWithChoices() {
		build();
		joinTwo();
		int mark = outbox.mark();
		startChoice();
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("to " + id).containsExactly("settings", "prepare", "roster");
			assertThat(sent.get(0).message()).isEqualTo(CHOICE_SETTINGS);
			assertThat(coverUrls(sent.get(1).message())).hasSize(6);
		}
		assertThat(coverUrls(prepareOf(P1))).isEqualTo(coverUrls(prepareOf(P2)));
	}

	@Test
	void aFilterAndTheModeGoInOneSettings() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, new DrawFilter(Set.of("OP"), null, null, DrawFilter.EASY), AnswerMode.CHOICE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).containsExactly("settings", "prepare", "roster");
			assertThat(sent.get(0).message())
					.isEqualTo(msg("settings", "types", List.of("OP"), "tier", "easy", "answerMode", "choice"));
			assertThat(coverUrls(sent.get(1).message())).hasSize(4);
		}
	}

	@Test
	void anExplicitChatStartIsTodaysGame() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		for (String id : List.of(P1, P2)) {
			assertThat(types(toSince(mark, id))).containsExactly("prepare", "roster");
			assertThat(prepareOf(id)).doesNotContainKey("choices");
		}
	}

	@Test
	void aNonHostCannotStartAChoiceGame() {
		build();
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P2, 5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P2);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(msg("error", "code", "not-host"));
	}

	@Test
	void theModeCannotChangeDuringAGame() {
		build();
		joinTwo();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHAT);
		int mark = outbox.mark();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(msg("error", "code", "in-game"));
		toAudible(0);
		mark = outbox.mark();
		lobby.pick(P1, 0, 0);
		assertThat(outbox.since(mark)).as("the game is still a chat game").isEmpty();
	}

	@Test
	void theModeHoldsOnlyForItsGame() {
		build();
		joinTwo();
		startChoice();
		for (int k = 0; k < 5; k++) {
			skipSong(k);
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		int mark = outbox.mark();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(prepareOf(P1)).doesNotContainKey("choices");
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		assertThat(types(toSince(mark, P3))).containsExactly("joined", "snapshot", "roster");
		assertThat(settingsSince(mark)).isEmpty();
	}

	@Test
	void tooFewCoveredGroupsGiveEveryMemberNotEnoughSongs() {
		List<ChoiceFixture.Spec> specs = new ArrayList<>(ChoiceFixture.coveredSingles(0, 3));
		specs.addAll(ChoiceFixture.uncoveredSingles(100, 10));
		build(specs);
		joinTwo();
		int mark = outbox.mark();
		lobby.start(P1, 5, DrawFilter.NONE, AnswerMode.CHOICE);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		for (String id : List.of(P1, P2)) {
			assertThat(toSince(mark, id)).extracting(Sent::message).containsExactly(
					Map.of("type", "error", "code", "not-enough-songs", "available", 3, "unit", "series"));
		}
	}

	// ---- pick ----

	@Test
	void aPickIsForwardedAndItsResultGoesToTheSenderOnly() {
		build();
		joinTwo();
		startChoice();
		int wrong = wrongPosition();
		toAudible(0);
		int mark = outbox.mark();
		lobby.pick(P2, 0, wrong);
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P2);
		assertThat(outbox.since(mark)).extracting(Sent::message)
				.containsExactly(msg("pick-result", "index", 0, "choice", wrong));

		mark = outbox.mark();
		lobby.pick(P1, 0, correctPosition());
		for (String id : List.of(P1, P2)) {
			List<Sent> sent = toSince(mark, id);
			assertThat(types(sent)).as("to " + id).startsWith("correct", "reveal", "scores");
			assertThat(sent.get(0).message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
			assertThat(sent.get(1).message().get("correctChoice")).isEqualTo(correctPosition());
		}
	}

	@Test
	void aPickFromALateJoinerOrADisconnectedMemberDoesNothing() {
		build();
		joinTwo();
		startChoice();
		int correct = correctPosition();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		toAudible(0);
		int mark = outbox.mark();
		lobby.pick(P3, 0, correct);
		lobby.disconnect(P2);
		int afterDisconnect = outbox.mark();
		lobby.pick(P2, 0, correct);
		lobby.pick("pid-unknown", 0, correct);
		assertThat(outbox.since(afterDisconnect)).isEmpty();
		assertThat(outbox.since(mark)).extracting(Sent::type).doesNotContain("correct", "pick-result", "reveal");
		assertThat(lobby.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// ---- snapshot ----

	@Test
	void aRejoinSnapshotCarriesTheSameChoicesAndThePlayersPickThenSettings() {
		build();
		joinTwo();
		startChoice();
		List<String> urls = coverUrls(prepareOf(P1));

		// In PREPARE: choices, nothing picked.
		lobby.disconnect(P2);
		int mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		List<Sent> sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(CHOICE_SETTINGS);
		Map<String, Object> song = snapshotSong(sent);
		assertThat(coverUrls(song)).isEqualTo(urls);
		assertThat(song).containsEntry("picked", null);

		// In PLAYING after a wrong pick.
		int wrong = wrongPosition();
		toAudible(0);
		lobby.pick(P2, 0, wrong);
		lobby.disconnect(P2);
		mark = outbox.mark();
		assertThat(lobby.reconnect(P2)).isTrue();
		sent = toSince(mark, P2);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		song = snapshotSong(sent);
		assertThat(coverUrls(song)).isEqualTo(urls);
		assertThat(song.get("picked")).isEqualTo(wrong);
		assertThat(song).doesNotContainKey("lockedOut");

		// A replaced socket of the player who did not pick.
		mark = outbox.mark();
		assertThat(lobby.reconnect(P1)).isTrue();
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings");
		song = snapshotSong(sent);
		assertThat(coverUrls(song)).isEqualTo(urls);
		assertThat(song).containsEntry("picked", null);

		// The rejoined player is still locked out.
		mark = outbox.mark();
		lobby.pick(P2, 0, correctPosition());
		assertThat(outbox.since(mark)).extracting(Sent::message)
				.containsExactly(msg("error", "code", "already-picked", "for", "pick"));
	}

	@Test
	void aLateJoinerGetsTheChoicesInItsSnapshotThenSettingsOnce() {
		build();
		joinTwo();
		startChoice();
		List<String> urls = coverUrls(prepareOf(P1));
		toAudible(0);
		int mark = outbox.mark();
		assertThat(lobby.join(P3, "Hana")).isEqualTo("Hana");
		List<Sent> sent = toSince(mark, P3);
		assertThat(types(sent)).containsExactly("joined", "snapshot", "settings", "roster");
		assertThat(sent.get(2).message()).isEqualTo(CHOICE_SETTINGS);
		Map<String, Object> song = snapshotSong(sent);
		assertThat(coverUrls(song)).isEqualTo(urls);
		assertThat(song).containsEntry("picked", null);
		assertThat(settingsSince(mark)).hasSize(1);
	}

}
