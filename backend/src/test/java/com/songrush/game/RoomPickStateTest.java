package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 122 (D-130) at Room level, on the cycle 036 fakes: the pick count of a choice room
// (docs/cycles/122-pick-count.md, criteria 1 to 9). Two messages to all, in a choice game only,
// first come and ranked alike: pickState{index, picked, total} (connected Room players with an
// accepted pick of this song, out of the connected Room players; watchers never count) and
// wrong{index, name}. Rule 1: an accepted pick sends pickState first, then correct or the sender's
// pick-result; a wrong pick is followed by wrong. Rule 2: a Room player's connection change during
// PLAYING sends pickState as its first frame (nothing after the song ended, when paused or with
// total 0). Rule 3: every start of a choice song is followed at once by pickState.
// Room.currentSong(playerId) of a choice song gains pickState {picked, total}. Built like
// RoomChoiceTest and RoomRankedChoiceTest: ChoiceFixture with 30 covered anime; the correct position
// is found from the token in prepare.audioUrl.
class RoomPickStateTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final String PICK_STATE = "pickState";

	private static final String WRONG = "wrong";

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final AudioTokens tokens = new AudioTokens(clock);

	private RecordingOutbox outbox;

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private void build(PlayerRef... members) {
		build(dir, false, members);
	}

	// A fresh room with its own outbox, so one test can run more than one game.
	private void build(Path folder, boolean prefetchOnReveal, PlayerRef... members) {
		outbox = new RecordingOutbox();
		built = ChoiceFixture.build(folder, ChoiceFixture.coveredSingles(0, 30));
		players = List.of(members);
		room = new Room(built.catalog(), tokens, scheduler, clock, new Random(100), outbox, players, built.covers(),
				prefetchOnReveal);
	}

	private void start(AnswerMode answer, GameMode game) {
		room.start(5, DrawFilter.NONE, answer, game);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	private void startChoice() {
		start(AnswerMode.CHOICE, GameMode.FIRST);
	}

	private void startRankedChoice() {
		start(AnswerMode.CHOICE, GameMode.RANKED);
	}

	// ---- helpers ----

	private Map<String, Object> lastPrepare() {
		return outbox.last("prepare").message();
	}

	private int currentAnimeIndex() {
		String url = (String) lastPrepare().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return built.animeIndexOfTheme(themeId);
	}

	@SuppressWarnings("unchecked")
	private List<String> choices() {
		List<String> urls = new ArrayList<>();
		for (Object choice : (List<Object>) lastPrepare().get("choices")) {
			urls.add((String) ((Map<String, Object>) choice).get("coverUrl"));
		}
		return urls;
	}

	private int correctPosition() {
		int pos = choices().indexOf(ChoiceFixture.coverUrl(currentAnimeIndex()));
		assertThat(pos).as("the current anime's cover is a choice").isGreaterThanOrEqualTo(0);
		return pos;
	}

	private int wrongPosition() {
		return correctPosition() == 0 ? 1 : 0;
	}

	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// Readies everyone and waits out the lead, so the song is audible.
	private void toAudible(int index) {
		readyAll(index);
		scheduler.advance(Room.LEAD);
	}

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

	private static Map<String, Object> pickState(int index, int picked, int total) {
		return msg(PICK_STATE, "index", index, "picked", picked, "total", total);
	}

	private static Map<String, Object> wrong(int index, String name) {
		return msg(WRONG, "index", index, "name", name);
	}

	// The counts of a snapshot's song: the nested {picked, total}.
	private static Map<String, Object> counts(int picked, int total) {
		return row("picked", picked, "total", total);
	}

	private static Map<String, Object> pickError(String code) {
		return msg("error", "code", code, "for", "pick");
	}

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private static List<Map<String, Object>> messages(List<Sent> sent) {
		return sent.stream().map(Sent::message).toList();
	}

	private List<Map<String, Object>> pickStatesSince(int mark) {
		return outbox.since(mark).stream().filter(s -> PICK_STATE.equals(s.type())).map(Sent::message).toList();
	}

	// Both players vote, so the song ends with no winner (two connected Room players).
	private void skipByBoth(int index) {
		room.skip(MINA.id(), index);
		room.skip(JOON.id(), index);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- an accepted pick (rule 1) ----

	// Criterion 1.
	@Test
	void aWrongPickSendsTheCountToAllTheResultToTheSenderAndTheNameToAll() {
		build(MINA, JOON, HANA);
		startChoice();
		int wrong = wrongPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "pick-result", WRONG);
		assertThat(sent.get(0).toAll()).as("pickState goes to all").isTrue();
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 1, 3));
		assertThat(sent.get(0).message().keySet()).containsExactlyInAnyOrder("type", "index", "picked", "total");
		assertThat(sent.get(1).to()).as("pick-result goes to its sender").isEqualTo(JOON.id());
		assertThat(sent.get(1).message()).isEqualTo(msg("pick-result", "index", 0, "choice", wrong));
		assertThat(sent.get(2).toAll()).as("wrong goes to all").isTrue();
		assertThat(sent.get(2).message()).isEqualTo(wrong(0, "Joon"));
		assertThat(sent.get(2).message().keySet()).containsExactlyInAnyOrder("type", "index", "name");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// A second wrong pick counts on.
		mark = outbox.mark();
		room.pick(HANA.id(), 0, wrong);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "pick-result", WRONG);
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 2, 3));
		assertThat(sent.get(1).to()).isEqualTo(HANA.id());
		assertThat(sent.get(2).message()).isEqualTo(wrong(0, "Hana"));
	}

	// Criterion 2.
	@Test
	void aCorrectPickInFirstComeSendsTheCountThenCorrectRevealAndScores() {
		build(MINA, JOON, HANA);
		startChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, wrongPosition());
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "correct", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 2, 3));
		assertThat(sent.get(1).message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
		assertThat(sent.get(2).message().get("winner")).isEqualTo("Mina");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(outbox.ofType(WRONG)).as("only Joon picked wrong").extracting(Sent::message)
				.containsExactly(wrong(0, "Joon"));
	}

	// Criterion 3.
	@Test
	void rankedPicksSendTheCountFirstAndOnlyAWrongPickIsNamed() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		int wrong = wrongPosition();
		toAudible(0);

		// A correct pick while others have not picked: no wrong, the song goes on.
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "correct");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 1, 3));
		assertThat(sent.get(1).message()).isEqualTo(msg("correct", "index", 0, "name", "Mina", "rank", 1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// A wrong pick.
		mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "pick-result", WRONG);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 2, 3));
		assertThat(sent.get(1).to()).isEqualTo(JOON.id());
		assertThat(sent.get(1).message()).isEqualTo(msg("pick-result", "index", 0, "choice", wrong));
		assertThat(sent.get(2).toAll()).isTrue();
		assertThat(sent.get(2).message()).isEqualTo(wrong(0, "Joon"));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// The last player picks wrong: the song ends after wrong.
		mark = outbox.mark();
		room.pick(HANA.id(), 0, wrong);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "pick-result", WRONG, "reveal", "scores");
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 3, 3));
		assertThat(sent.get(1).to()).isEqualTo(HANA.id());
		assertThat(sent.get(2).toAll()).isTrue();
		assertThat(sent.get(2).message()).isEqualTo(wrong(0, "Hana"));
		assertThat(sent.get(3).message().get("winner")).isEqualTo("Mina");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// ---- refused picks ----

	// Criterion 4. The accepted pick in the middle shows that no refused pick was counted.
	@Test
	void aRefusedPickSendsNeitherMessageAndIsNotCounted() {
		build(MINA, JOON, HANA);
		startChoice();
		int correct = correctPosition();
		int wrong = wrongPosition();
		int k = choices().size();
		room.addPlayer(SORA);
		readyAll(0);
		long deadline = ((Number) outbox.last("start").message().get("deadlineAt")).longValue();

		int mark = outbox.mark();
		room.pick(MINA.id(), 0, wrong);
		assertThat(messages(outbox.since(mark))).as("before startAt").containsExactly(pickError("too-early"));
		scheduler.advance(Room.LEAD);

		mark = outbox.mark();
		room.pick(SORA.id(), 0, wrong);
		assertThat(outbox.since(mark)).as("a watcher").isEmpty();
		room.pick(MINA.id(), 1, wrong);
		assertThat(outbox.since(mark)).as("another index").isEmpty();
		room.pick(MINA.id(), 0, k);
		room.pick(MINA.id(), 0, -1);
		assertThat(outbox.since(mark)).as("a position out of range").isEmpty();

		room.setConnected(HANA.id(), false);
		mark = outbox.mark();
		room.pick(HANA.id(), 0, wrong);
		assertThat(outbox.since(mark)).as("a disconnected player").isEmpty();
		room.setConnected(HANA.id(), true);

		mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		assertThat(messages(outbox.since(mark))).as("the first accepted pick").containsExactly(pickState(0, 1, 3),
				msg("pick-result", "index", 0, "choice", wrong), wrong(0, "Joon"));

		mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		room.pick(JOON.id(), 0, wrong);
		assertThat(messages(outbox.since(mark))).as("a second pick").containsExactly(pickError("already-picked"),
				pickError("already-picked"));

		// The clock reaches the deadline before the deadline task has run.
		clock.advance(Duration.ofMillis(deadline - clock.millis()));
		mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertThat(types(outbox.since(mark))).as("at the deadline").containsExactly("reveal", "scores");

		assertThat(outbox.ofType(WRONG)).extracting(Sent::message).containsExactly(wrong(0, "Joon"));
	}

	@Test
	void aPickWhilePausedOrOutsidePlayingSendsNeitherMessage() {
		build(MINA, JOON);
		startChoice();
		int correct = correctPosition();
		int wrong = wrongPosition();
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, wrong);
		assertThat(outbox.since(mark)).as("in PREPARE").isEmpty();

		toAudible(0);
		room.pick(JOON.id(), 0, wrong);
		room.pause();
		mark = outbox.mark();
		room.pick(MINA.id(), 0, wrong);
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		assertThat(outbox.since(mark)).as("paused: no pick and no count of a connection change").isEmpty();

		// The resumed song starts with the kept pick counted, and the refused pick is not.
		room.resume();
		mark = outbox.mark();
		readyAll(0);
		assertThat(types(outbox.since(mark))).containsExactly("start", PICK_STATE);
		assertThat(outbox.since(mark).get(1).message()).isEqualTo(pickState(0, 1, 2));
		scheduler.advance(Room.LEAD);

		mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertThat(types(outbox.since(mark))).containsExactly(PICK_STATE, "correct", "reveal", "scores");
		assertThat(outbox.since(mark).get(0).message()).isEqualTo(pickState(0, 2, 2));
		mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		room.setConnected(JOON.id(), false);
		room.setConnected(JOON.id(), true);
		assertThat(outbox.since(mark)).as("in REVEAL: no pick and no count of a connection change").isEmpty();
	}

	// ---- a chat game ----

	// Criterion 5, and the chat half of criteria 7 and 8.
	@Test
	void aChatGameNeverSendsTheCountOrAWrongName() {
		for (GameMode game : GameMode.values()) {
			build(dir.resolve("chat-" + game), false, MINA, JOON, HANA);
			start(AnswerMode.CHAT, game);
			String title = ChoiceFixture.title(currentAnimeIndex());
			assertThat(room.currentSong(MINA.id())).as(game + " in PREPARE").doesNotContainKey(PICK_STATE);
			int mark = outbox.mark();
			readyAll(0);
			assertThat(types(outbox.since(mark))).as(game + ": a chat song's start is alone").containsExactly("start");
			scheduler.advance(Room.LEAD);

			mark = outbox.mark();
			room.setConnected(HANA.id(), false);
			room.setConnected(HANA.id(), true);
			assertThat(outbox.since(mark)).as(game + ": a disconnect and a reconnect").isEmpty();
			room.pick(MINA.id(), 0, 0);
			room.chat(JOON.id(), "not the title");
			assertThat(room.currentSong(MINA.id())).as(game + " in PLAYING").doesNotContainKey(PICK_STATE);
			room.chat(MINA.id(), title);
			assertThat(outbox.ofType("correct")).as(game + ": the title scored").hasSize(1);
			room.setConnected(JOON.id(), false);
			room.setConnected(JOON.id(), true);

			assertThat(types(outbox.all())).as(game.toString()).doesNotContain(PICK_STATE, WRONG);
		}
	}

	// ---- a connection change during PLAYING (rule 2) ----

	// Criterion 6.
	@Test
	void aDisconnectLowersTheTotalAndThePickedOfAPlayerWhoPickedAndTheReconnectRestoresThem() {
		build(MINA, JOON, HANA);
		startChoice();
		int wrong = wrongPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, wrong);

		// A player who has not picked.
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(messages(sent)).containsExactly(pickState(0, 1, 2));
		assertThat(sent.get(0).toAll()).isTrue();
		mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		assertThat(outbox.since(mark)).as("no change, nothing sent").isEmpty();
		room.setConnected(HANA.id(), true);
		sent = outbox.since(mark);
		assertThat(messages(sent)).containsExactly(pickState(0, 1, 3));
		assertThat(sent.get(0).toAll()).isTrue();
		mark = outbox.mark();
		room.setConnected(HANA.id(), true);
		assertThat(outbox.since(mark)).as("no change, nothing sent").isEmpty();

		// A player who has picked: the pick is kept, but not counted until the player is back.
		room.setConnected(JOON.id(), false);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 0, 2));
		mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 1, 3));
		mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		assertThat(messages(outbox.since(mark))).as("the pick was kept").containsExactly(pickError("already-picked"));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// Criterion 6: watchers are never counted and their connection changes send nothing.
	@Test
	void aWatchersConnectionChangeSendsNothingAndAWatcherIsNeverCounted() {
		build(MINA, JOON);
		startChoice();
		toAudible(0);
		room.addPlayer(SORA);
		int mark = outbox.mark();
		room.setConnected(SORA.id(), false);
		room.setConnected(SORA.id(), true);
		room.setConnected("p-unknown", false);
		assertThat(outbox.since(mark)).as("a watcher and an unknown id").isEmpty();

		room.setConnected(JOON.id(), false);
		assertThat(messages(outbox.since(mark))).as("two Room players, one left").containsExactly(pickState(0, 0, 1));
		mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 0, 2));
		mark = outbox.mark();
		room.pick(MINA.id(), 0, wrongPosition());
		assertThat(outbox.since(mark).get(0).message()).isEqualTo(pickState(0, 1, 2));
	}

	// Criterion 6, rule 2: nothing with total 0, nothing after the song has ended.
	@Test
	void nothingIsSentWhenNobodyIsConnectedOrAfterTheSongHasEnded() {
		build(MINA, JOON);
		startChoice();
		toAudible(0);
		room.pick(MINA.id(), 0, wrongPosition());
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 1, 1));
		mark = outbox.mark();
		room.setConnected(MINA.id(), false);
		assertThat(outbox.since(mark)).as("total 0").isEmpty();
		room.setConnected(MINA.id(), true);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 1, 1));

		// Mina alone passes the skip vote; Joon returns during the reveal.
		room.skip(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		room.setConnected(JOON.id(), false);
		assertThat(outbox.since(mark)).as("in REVEAL").isEmpty();
	}

	// Criterion 6.
	@Test
	void rankedTheLastUnpickedPlayerDisconnectingSendsTheCountThenTheReveal() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(HANA.id(), 0, correct);
		room.pick(MINA.id(), 0, wrongPosition());
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 2, 2));
		assertThat(sent.get(1).message().get("winner")).isEqualTo("Hana");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// Criterion 6: the count comes before the skipState of the same change, and never after the reveal.
	@Test
	void aDisconnectThatPassesTheSkipVoteSendsTheCountBeforeSkipStateAndNothingAfterTheReveal() {
		build(MINA, JOON, HANA, SORA);
		startChoice();
		toAudible(0);
		room.pick(HANA.id(), 0, wrongPosition());
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(room.phase()).as("2 of 4 votes do not pass").isEqualTo(Room.Phase.PLAYING);
		int mark = outbox.mark();
		room.setConnected(SORA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "skipState", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 1, 3));
		assertThat(sent.get(1).message()).isEqualTo(msg("skipState", "index", 0, "votes", 2, "needed", 2));
		assertThat(sent.get(2).message().get("winner")).isNull();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// Rule 2 with a vote that does not pass: a reconnect, too, counts first.
	@Test
	void aReconnectWhileAVoteStandsSendsTheCountBeforeSkipState() {
		build(MINA, JOON, HANA);
		startChoice();
		toAudible(0);
		room.setConnected(HANA.id(), false);
		room.skip(MINA.id(), 0);
		assertThat(room.phase()).as("1 of 2 votes does not pass").isEqualTo(Room.Phase.PLAYING);
		int mark = outbox.mark();
		room.setConnected(HANA.id(), true);
		assertThat(messages(outbox.since(mark))).containsExactly(pickState(0, 0, 3),
				msg("skipState", "index", 0, "votes", 1, "needed", 2));
	}

	// ---- the snapshot ----

	// Criterion 7.
	@Test
	void currentSongOfAChoiceSongCarriesTheCurrentCounts() {
		build(MINA, JOON, HANA);
		startChoice();
		Map<String, Object> inPrepare = room.currentSong(MINA.id());
		assertThat(inPrepare.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "choices", "picked",
				PICK_STATE);
		assertThat(inPrepare.get(PICK_STATE)).isEqualTo(counts(0, 3));

		toAudible(0);
		int wrong = wrongPosition();
		room.pick(JOON.id(), 0, wrong);
		Map<String, Object> joon = room.currentSong(JOON.id());
		assertThat(joon.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt",
				"choices", "picked", PICK_STATE);
		assertThat(joon.get(PICK_STATE)).isEqualTo(counts(1, 3));
		// The older keys keep their meaning: the own pick position and the song count.
		assertThat(joon).containsEntry("picked", wrong).containsEntry("total", 5);
		assertThat(room.currentSong(MINA.id())).containsEntry("picked", null).containsEntry("total", 5);
		assertThat(room.currentSong(MINA.id()).get(PICK_STATE)).as("the same counts for everyone")
				.isEqualTo(counts(1, 3));

		room.setConnected(HANA.id(), false);
		assertThat(room.currentSong(MINA.id()).get(PICK_STATE)).isEqualTo(counts(1, 2));
		assertThat(room.currentSong(HANA.id()).get(PICK_STATE)).as("as it is when the snapshot is built")
				.isEqualTo(counts(1, 2));
		room.setConnected(JOON.id(), false);
		assertThat(room.currentSong(MINA.id()).get(PICK_STATE)).isEqualTo(counts(0, 1));
		room.addPlayer(SORA);
		Map<String, Object> watcher = room.currentSong(SORA.id());
		assertThat(watcher.get(PICK_STATE)).as("a watcher is not counted").isEqualTo(counts(0, 1));
		assertThat(watcher).containsEntry("picked", null);
	}

	// Criterion 7, ranked.
	@Test
	void currentSongOfARankedChoiceSongCarriesTheCurrentCounts() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		Map<String, Object> inPrepare = room.currentSong(MINA.id());
		assertThat(inPrepare.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "choices", "picked",
				"answered", "correct", PICK_STATE);
		assertThat(inPrepare.get(PICK_STATE)).isEqualTo(counts(0, 3));
		int correct = correctPosition();
		toAudible(0);
		room.pick(MINA.id(), 0, correct);
		room.pick(JOON.id(), 0, wrongPosition());
		assertThat(room.currentSong(HANA.id()).get(PICK_STATE)).isEqualTo(counts(2, 3));
		assertThat(room.currentSong(MINA.id())).containsEntry("picked", correct).containsEntry("answered", true);
	}

	// ---- every start (rule 3) ----

	// Criterion 8: all ready, the prepare timeout and the next songs alike.
	@Test
	void everyStartOfAChoiceSongIsFollowedAtOnceByTheCount() {
		for (GameMode game : GameMode.values()) {
			build(dir.resolve("starts-" + game), false, MINA, JOON);
			start(AnswerMode.CHOICE, game);
			for (int index = 0; index < 5; index++) {
				int wrong = wrongPosition();
				int mark = outbox.mark();
				if (index % 2 == 0) {
					readyAll(index);
				}
				else {
					room.ready(MINA.id(), index);
					scheduler.advance(Room.PREPARE_TIMEOUT);
					assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
				}
				List<Sent> sent = outbox.since(mark);
				assertThat(types(sent)).as(game + " song " + index).containsExactly("start", PICK_STATE);
				assertThat(sent.get(1).toAll()).isTrue();
				assertThat(sent.get(1).message()).as(game + " song " + index + ": a fresh song")
						.isEqualTo(pickState(index, 0, 2));
				scheduler.advance(Room.LEAD);
				// A pick of this song must not show in the next song's first count.
				room.pick(JOON.id(), index, wrong);
				skipByBoth(index);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			List<Sent> all = outbox.all();
			int starts = 0;
			for (int i = 0; i < all.size(); i++) {
				if ("start".equals(all.get(i).type())) {
					starts++;
					assertThat(all.get(i + 1).type()).as(game + ": the frame after start " + starts).isEqualTo(PICK_STATE);
				}
			}
			assertThat(starts).isEqualTo(5);
		}
	}

	// Criterion 8: the resume case of the spec, first come and ranked.
	@Test
	void aResumedSongStartsWithTheKeptPickAndThePlayersConnectedAtTheStart() {
		for (GameMode game : GameMode.values()) {
			build(dir.resolve("resume-" + game), false, MINA, JOON, HANA);
			start(AnswerMode.CHOICE, game);
			int wrong = wrongPosition();
			toAudible(0);
			room.pick(MINA.id(), 0, wrong);
			int mark = outbox.mark();
			room.setConnected(MINA.id(), false);
			room.setConnected(JOON.id(), false);
			room.setConnected(HANA.id(), false);
			assertThat(messages(outbox.since(mark))).as(game + ": the room empties").containsExactly(pickState(0, 0, 2),
					pickState(0, 0, 1));
			room.pause();

			room.setConnected(MINA.id(), true);
			room.resume();
			assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
			mark = outbox.mark();
			room.setConnected(JOON.id(), true);
			assertThat(outbox.since(mark)).as(game + ": a reconnect during PREPARE sends nothing").isEmpty();
			room.ready(MINA.id(), 0);
			room.ready(JOON.id(), 0);
			List<Sent> sent = outbox.since(mark);
			assertThat(types(sent)).as(game.toString()).containsExactly("start", PICK_STATE);
			assertThat(sent.get(1).message()).as(game.toString()).isEqualTo(pickState(0, 1, 2));
			assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		}
	}

	// Criterion 8: a late joiner promoted at the prepare and a disconnect during PREPARE.
	@Test
	void theCountAfterAStartHasTheLateJoinerAndLeavesOutAPlayerWhoLeftDuringPrepare() {
		build(MINA, JOON);
		startChoice();
		toAudible(0);
		room.addPlayer(HANA);
		skipByBoth(0);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(room.isPlayer(HANA.id())).isTrue();

		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		assertThat(outbox.since(mark)).as("a disconnect during PREPARE sends nothing").isEmpty();
		room.ready(MINA.id(), 1);
		room.ready(HANA.id(), 1);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("start", PICK_STATE);
		assertThat(sent.get(1).message()).isEqualTo(pickState(1, 0, 2));

		mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		assertThat(messages(outbox.since(mark))).as("three Room players now").containsExactly(pickState(1, 0, 3));
	}

	// Rule 3 in early mode (prefetch on reveal): the start after the reveal pause counts too.
	@Test
	void anEarlyPreparedSongStartsWithTheCountOfAFreshSong() {
		build(dir, true, MINA, JOON);
		startChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, wrongPosition());
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly(PICK_STATE, "correct", "reveal", "scores", "prepare");
		assertThat(sent.get(0).message()).isEqualTo(pickState(0, 2, 2));

		room.ready(MINA.id(), 1);
		room.ready(JOON.id(), 1);
		mark = outbox.mark();
		scheduler.advance(Room.REVEAL_PAUSE);
		sent = outbox.since(mark);
		assertThat(types(sent)).containsExactly("start", PICK_STATE);
		assertThat(sent.get(1).message()).isEqualTo(pickState(1, 0, 2));
	}

	// ---- secrecy ----

	// Criterion 9. The wrong pick uses a position (4 or 5) that no count of a three player song can equal.
	@Test
	void theCountAndTheWrongNameNeverCarryThePickedPosition() {
		for (GameMode game : GameMode.values()) {
			build(dir.resolve("secrecy-" + game), false, MINA, JOON, HANA);
			start(AnswerMode.CHOICE, game);
			int correct = correctPosition();
			int wrong = correct == 5 ? 4 : 5;
			assertThat(choices()).hasSize(6);
			toAudible(0);
			room.pick(JOON.id(), 0, wrong);
			room.setConnected(HANA.id(), false);
			room.setConnected(HANA.id(), true);
			room.pick(HANA.id(), 0, wrong);
			room.pick(MINA.id(), 0, correct);
			assertThat(room.phase()).as(game.toString()).isEqualTo(Room.Phase.REVEAL);

			int counts = 0;
			int names = 0;
			for (Sent sent : outbox.all()) {
				if ("reveal".equals(sent.type())) {
					break;
				}
				if ("pick-result".equals(sent.type())) {
					assertThat(sent.toAll()).as("pick-result goes to its sender only").isFalse();
					continue;
				}
				assertThat(sent.message()).as(game + " " + sent.type()).doesNotContainKeys("choice", "correctChoice");
				if (PICK_STATE.equals(sent.type())) {
					counts++;
					assertThat(sent.toAll()).isTrue();
					assertThat(sent.message().keySet()).containsExactlyInAnyOrder("type", "index", "picked", "total");
					assertThat(sent.message().values()).as(game + ": no picked position").doesNotContain(wrong);
				}
				else if (WRONG.equals(sent.type())) {
					names++;
					assertThat(sent.toAll()).isTrue();
					assertThat(sent.message().keySet()).containsExactlyInAnyOrder("type", "index", "name");
					assertThat(sent.message().values()).as(game + ": no picked position").doesNotContain(wrong);
				}
			}
			// The start, three accepted picks, a disconnect and a reconnect; two wrong picks.
			assertThat(counts).as(game + ": pickState frames before the reveal").isEqualTo(6);
			assertThat(names).as(game + ": wrong frames before the reveal").isEqualTo(2);
			assertThat(pickStatesSince(0)).as(game.toString()).containsExactly(pickState(0, 0, 3), pickState(0, 1, 3),
					pickState(0, 1, 2), pickState(0, 1, 3), pickState(0, 2, 3), pickState(0, 3, 3));
		}
	}

}
