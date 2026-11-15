package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.ChoiceFixture.Spec;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 100 (D-111) at Room level, on the cycle 036 fakes: the choice answer mode
// (docs/cycles/100-choice-answers.md, with the review amendments A1-A11 and the owner's rounds 1
// and 2: A1 and O1 are void, O2, O3 and O4 stand).
//
// Declared rewrite (owner round 2): anExactTitleByChatInAChoiceRoomWins and
// aPlayerLockedOutOfPicksCanStillWinByChat (O1) are replaced by
// anExactTitleByChatInAChoiceRoomIsJustAChatLine, anExactTitleByChatBeforeStartAtGoesToTheSenderOnly
// and anExactTitleByChatFromAWatcherGoesToTheWatcherOnly (O4: chat never scores in a choice room).
// Room.start(songCount, filter, AnswerMode.CHOICE) puts choices in prepare; Room.pick(playerId,
// index, choice) answers by position; Room.currentSong(playerId) adds choices and that player's
// picked. The fixture is 30 covered anime, each its own group with one OP (ChoiceFixture); the
// current song is found by resolving the token in prepare.audioUrl.
class RoomChoiceTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final Pattern COVER_URL = Pattern.compile("^/cover/[A-Za-z0-9_-]{1,64}$");

	private static final List<String> PREPARE_KEYS = List.of("type", "index", "total", "audioUrl");

	private static final List<String> START_KEYS = List.of("type", "index", "startAt", "deadlineAt", "serverTime");

	private static final DrawFilter EASY = new DrawFilter(null, null, null, DrawFilter.EASY);

	private static final DrawFilter NORMAL = new DrawFilter(null, null, null, DrawFilter.NORMAL);

	private static final DrawFilter HARD = new DrawFilter(null, null, null, DrawFilter.HARD);

	private static final ObjectMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Built built;

	private Room room;

	private List<PlayerRef> players;

	private void build(PlayerRef... members) {
		build(ChoiceFixture.coveredSingles(0, 30), 100, members);
	}

	private void build(List<Spec> specs, long seed, PlayerRef... members) {
		built = ChoiceFixture.build(dir, specs);
		players = List.of(members);
		room = new Room(built.catalog(), tokens, scheduler, clock, new Random(seed), outbox, players, built.covers());
	}

	private void startChoice(DrawFilter filter) {
		room.start(5, filter, AnswerMode.CHOICE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
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
	private static List<String> coverUrls(Map<String, Object> carrier) {
		assertThat(carrier).containsKey("choices");
		List<Object> choices = (List<Object>) carrier.get("choices");
		List<String> urls = new ArrayList<>();
		for (Object choice : choices) {
			assertThat(choice).isInstanceOf(Map.class);
			urls.add((String) ((Map<String, Object>) choice).get("coverUrl"));
		}
		return urls;
	}

	private List<String> choices() {
		return coverUrls(lastPrepare());
	}

	private int correctPosition() {
		int pos = choices().indexOf(ChoiceFixture.coverUrl(currentAnimeIndex()));
		assertThat(pos).as("the current anime's cover is a choice").isGreaterThanOrEqualTo(0);
		return pos;
	}

	private int wrongPosition() {
		return correctPosition() == 0 ? 1 : 0;
	}

	// The fixture players, and HANA once she is a promoted Room player.
	private void readyAll(int index) {
		for (PlayerRef p : players) {
			room.ready(p.id(), index);
		}
		if (room.isPlayer(HANA.id()) && !players.contains(HANA)) {
			room.ready(HANA.id(), index);
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
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

	private static Map<String, Object> pickError(String code) {
		return msg("error", "code", code, "for", "pick");
	}

	private static String json(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	private void assertNothingSince(int mark, String why) {
		assertThat(outbox.since(mark)).as(why).isEmpty();
	}

	// ---- prepare, start and reveal shapes ----

	@Test
	void aChoiceRoomsPrepareIsTodaysKeysPlusChoicesOfCoverUrlsOnly() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		Map<String, Object> prepare = lastPrepare();
		List<String> keys = new ArrayList<>(PREPARE_KEYS);
		keys.add("choices");
		assertThat(prepare.keySet()).containsExactlyInAnyOrderElementsOf(keys);
		assertThat(outbox.last("prepare").toAll()).isTrue();
		@SuppressWarnings("unchecked")
		List<Object> choices = (List<Object>) prepare.get("choices");
		assertThat(choices).hasSize(6);
		for (Object choice : choices) {
			@SuppressWarnings("unchecked")
			Map<String, Object> c = (Map<String, Object>) choice;
			assertThat(c.keySet()).containsExactly("coverUrl");
			assertThat((String) c.get("coverUrl")).matches(COVER_URL);
		}
		assertThat(choices()).doesNotHaveDuplicates();
	}

	@Test
	void aChoiceRoomsStartAndRevealShapes() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		readyAll(0);
		assertThat(outbox.last("start").message().keySet()).containsExactlyElementsOf(START_KEYS);
		scheduler.advance(Room.LEAD);
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		Map<String, Object> reveal = outbox.last("reveal").message();
		assertThat(reveal.get("correctChoice")).isEqualTo(correct);
		assertThat(reveal.get("coverUrl")).isEqualTo(choices().get(correct));
		assertThat(reveal.get("winner")).isNull();
	}

	@Test
	void aChatRoomHasNoChoicesNoCorrectChoiceAndNoSettings() {
		for (AnswerMode mode : new AnswerMode[] {AnswerMode.CHAT, null}) {
			RecordingOutbox out = new RecordingOutbox();
			built = ChoiceFixture.build(dir.resolve(mode == null ? "two" : "one"), ChoiceFixture.coveredSingles(0, 30));
			Room chatRoom = new Room(built.catalog(), tokens, scheduler, clock, new Random(5), out, List.of(MINA),
					built.covers());
			if (mode == null) {
				chatRoom.start(5, DrawFilter.NONE);
			}
			else {
				chatRoom.start(5, DrawFilter.NONE, mode);
			}
			assertThat(out.last("prepare").message().keySet()).as("mode " + mode).containsExactlyElementsOf(PREPARE_KEYS);
			assertThat(chatRoom.currentSong(MINA.id())).isEqualTo(chatRoom.currentSong());
			chatRoom.ready(MINA.id(), 0);
			assertThat(out.last("start").message().keySet()).containsExactlyElementsOf(START_KEYS);
			assertThat(chatRoom.currentSong(MINA.id())).isEqualTo(chatRoom.currentSong());
			scheduler.advance(Room.LEAD);
			chatRoom.skip(MINA.id(), 0);
			assertThat(out.last("reveal").message()).doesNotContainKey("correctChoice");
			assertThat(out.ofType("settings")).isEmpty();
		}
	}

	@Test
	void aChoiceRoomWithNoFilterSendsSettingsWithTheModeBeforeThePrepare() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		List<Sent> all = outbox.all();
		assertThat(all.get(0).message()).isEqualTo(msg("settings", "answerMode", "choice"));
		assertThat(all.get(0).toAll()).isTrue();
		assertThat(all.get(1).type()).isEqualTo("prepare");
	}

	@Test
	void aChoiceRoomWithAFilterSendsTheFilterFieldsAndTheMode() {
		build(MINA);
		room.start(5, new DrawFilter(Set.of("OP"), 1990, 2010, DrawFilter.HARD), AnswerMode.CHOICE);
		Map<String, Object> expected = msg("settings", "types", List.of("OP"), "yearFrom", 1990, "yearTo", 2010,
				"tier", "hard", "answerMode", "choice");
		assertThat(outbox.ofType("settings")).hasSize(1);
		assertThat(outbox.last("settings").message()).isEqualTo(expected);
	}

	// ---- k per tier ----

	@Test
	void theNumberOfChoicesFollowsTheTier() {
		// NORMAL equals NONE (the record maps "normal" to no tier); both are listed for clarity.
		List<Map.Entry<DrawFilter, Integer>> expected = List.of(Map.entry(EASY, 4), Map.entry(NORMAL, 6),
				Map.entry(DrawFilter.NONE, 6), Map.entry(HARD, 8));
		int run = 0;
		for (Map.Entry<DrawFilter, Integer> e : expected) {
			RecordingOutbox out = new RecordingOutbox();
			Built b = ChoiceFixture.build(dir.resolve("k" + run++), ChoiceFixture.coveredSingles(0, 30));
			Room r = new Room(b.catalog(), tokens, scheduler, clock, new Random(run), out, List.of(MINA), b.covers());
			r.start(5, e.getKey(), AnswerMode.CHOICE);
			for (int index = 0; index < 5; index++) {
				assertThat(coverUrls(out.last("prepare").message())).as(e.getKey() + " song " + index)
						.hasSize(e.getValue());
				r.ready(MINA.id(), index);
				r.skip(MINA.id(), index);
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(r.phase()).isEqualTo(Room.Phase.LOBBY);
		}
	}

	// ---- currentSong ----

	@Test
	void currentSongCarriesTheSameChoicesAndThePlayersPick() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		List<String> urls = choices();
		Map<String, Object> inPrepare = room.currentSong(MINA.id());
		assertThat(inPrepare.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "choices", "picked",
				"pickState");
		assertThat(coverUrls(inPrepare)).isEqualTo(urls);
		assertThat(inPrepare.get("picked")).isNull();

		toAudible(0);
		int wrong = wrongPosition();
		room.pick(JOON.id(), 0, wrong);
		Map<String, Object> joon = room.currentSong(JOON.id());
		assertThat(joon.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt",
				"choices", "picked", "pickState");
		assertThat(coverUrls(joon)).isEqualTo(urls);
		assertThat(joon.get("picked")).isEqualTo(wrong);
		assertThat(room.currentSong(MINA.id()).get("picked")).isNull();
		// The no-argument form stays what it was: nothing of the choices.
		assertThat(room.currentSong()).doesNotContainKeys("choices", "picked");
	}

	// ---- picks ----

	@Test
	void aCorrectPickScoresEndsTheSongAndIsMarkedInTheReveal() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("pickState", "correct", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(1).message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
		assertThat(sent.get(2).message().get("winner")).isEqualTo("Mina");
		assertThat(sent.get(2).message().get("correctChoice")).isEqualTo(correct);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.scores()).containsEntry(MINA.id(), 1).containsEntry(JOON.id(), 0);
	}

	@Test
	void aWrongPickGoesToTheSenderOnlyAndASecondPickIsAlreadyPicked() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		int wrong = wrongPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		List<Sent> sent = outbox.since(mark);
		// Cycle 122: the count and the name go to all; the picked position still reaches the sender only.
		assertThat(sent).extracting(Sent::type).containsExactly("pickState", "pick-result", "wrong");
		assertThat(sent.get(1).to()).isEqualTo(JOON.id());
		assertThat(sent.get(1).message()).isEqualTo(msg("pick-result", "index", 0, "choice", wrong));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		for (int choice : new int[] {correct, wrong}) {
			mark = outbox.mark();
			room.pick(JOON.id(), 0, choice);
			sent = outbox.since(mark);
			assertThat(sent).hasSize(1);
			assertThat(sent.get(0).to()).isEqualTo(JOON.id());
			assertThat(sent.get(0).message()).isEqualTo(pickError("already-picked"));
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(JOON.id(), 0);
	}

	@Test
	void aPickBeforeStartAtIsTooEarlyAndDoesNotUseThePick() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		readyAll(0);
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(MINA.id());
		assertThat(sent.get(0).message()).isEqualTo(pickError("too-early"));
		assertThat(room.currentSong(MINA.id()).get("picked")).isNull();

		scheduler.advance(Room.LEAD);
		room.pick(MINA.id(), 0, correct);
		assertThat(outbox.last("correct").message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
		assertThat(room.scores()).containsEntry(MINA.id(), 1);
	}

	@Test
	void aPickOutsidePlayingSendsNothing() {
		build(MINA, JOON);
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, 0);
		assertNothingSince(mark, "in LOBBY before any game");

		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertNothingSince(mark, "in PREPARE");
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		toAudible(0);
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertNothingSince(mark, "in REVEAL");
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void aWatcherPickSendsNothing() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		room.addPlayer(HANA);
		readyAll(0);
		int mark = outbox.mark();
		// Before startAt too: the watcher rule comes before too-early.
		room.pick(HANA.id(), 0, correct);
		assertNothingSince(mark, "watcher before startAt");
		scheduler.advance(Room.LEAD);
		room.pick(HANA.id(), 0, correct);
		assertNothingSince(mark, "watcher");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aPickWithTheWrongIndexSendsNothing() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		readyAll(0);
		int mark = outbox.mark();
		room.pick(MINA.id(), 1, correct);
		assertNothingSince(mark, "wrong index before startAt");
		scheduler.advance(Room.LEAD);
		for (int index : new int[] {1, -1, 5}) {
			room.pick(MINA.id(), index, correct);
		}
		assertNothingSince(mark, "wrong index");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		room.pick(MINA.id(), 0, correct);
		assertThat(room.scores()).containsEntry(MINA.id(), 1);
	}

	@Test
	void aPositionOutOfRangeSendsNothingAndLeavesThePick() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int k = choices().size();
		int wrong = wrongPosition();
		toAudible(0);
		int mark = outbox.mark();
		for (int choice : new int[] {-1, k, k + 1, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
			room.pick(MINA.id(), 0, choice);
		}
		assertNothingSince(mark, "bad positions");
		assertThat(room.currentSong(MINA.id()).get("picked")).isNull();
		room.pick(MINA.id(), 0, wrong);
		assertThat(outbox.last("pick-result").message()).isEqualTo(msg("pick-result", "index", 0, "choice", wrong));
		// A bad position comes before already-picked.
		mark = outbox.mark();
		room.pick(MINA.id(), 0, k);
		assertNothingSince(mark, "bad position after a pick");
	}

	@Test
	void aPickInAChatRoomSendsNothing() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT);
		toAudible(0);
		int mark = outbox.mark();
		for (int choice = 0; choice < 8; choice++) {
			room.pick(MINA.id(), 0, choice);
		}
		assertNothingSince(mark, "chat room");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void aPickWhilePausedSendsNothing() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		toAudible(0);
		room.pause();
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertNothingSince(mark, "paused");
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void aPickAtOrAfterTheDeadlineEndsTheSongWithNoWinnerAndSendsNoPickFrame() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		readyAll(0);
		long deadline = ((Number) outbox.last("start").message().get("deadlineAt")).longValue();
		// The clock reaches the deadline before the deadline task has run.
		clock.advance(Duration.ofMillis(deadline - clock.millis()));
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("reveal", "scores");
		assertThat(sent.get(0).message().get("winner")).isNull();
		assertThat(sent.get(0).message().get("correctChoice")).isEqualTo(correct);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void concurrentCorrectPicksTheFirstByArrivalWins() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("pickState", "correct", "reveal", "scores");
		assertThat(sent.get(1).message().get("name")).isEqualTo("Joon");
		assertThat(room.scores()).containsEntry(JOON.id(), 1).containsEntry(MINA.id(), 0);
	}

	// O2: no everyone-wrong ending.
	@Test
	void everyPlayerPickingWrongDoesNotEndTheSong() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int wrong = wrongPosition();
		toAudible(0);
		room.pick(MINA.id(), 0, wrong);
		room.pick(JOON.id(), 0, wrong);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("reveal")).isEmpty();
		room.setConnected(JOON.id(), false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("reveal")).isEmpty();
	}

	@Test
	void aWrongPickDoesNotEndTheSongAndAnotherPlayerCanStillWin() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		toAudible(0);
		room.pick(MINA.id(), 0, wrongPosition());
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		room.pick(JOON.id(), 0, correct);
		assertThat(outbox.last("correct").message().get("name")).isEqualTo("Joon");
		assertThat(outbox.last("reveal").message().get("winner")).isEqualTo("Joon");
	}

	@Test
	void picksResetWhenTheSongIndexChanges() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		toAudible(0);
		room.pick(MINA.id(), 0, wrongPosition());
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(room.currentSong(MINA.id()).get("picked")).isNull();
		int correct = correctPosition();
		toAudible(1);
		room.pick(MINA.id(), 1, correct);
		assertThat(outbox.last("correct").message()).isEqualTo(msg("correct", "index", 1, "name", "Mina"));
	}

	// A4: a resume's re-prepare keeps the choices and the picks; too-early comes before already-picked.
	@Test
	void aResumeKeepsTheChoicesAndThePick() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		List<String> urls = choices();
		int wrong = wrongPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, wrong);
		room.pause();
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(0);
		assertThat(choices()).as("the re-prepare's choices").isEqualTo(urls);
		assertThat(room.currentSong(JOON.id()).get("picked")).isEqualTo(wrong);

		readyAll(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, correctPosition());
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(pickError("too-early"));
		scheduler.advance(Room.LEAD);
		mark = outbox.mark();
		room.pick(JOON.id(), 0, correctPosition());
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(pickError("already-picked"));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aPickCostsNoChatToken() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		readyAll(0);
		for (int i = 0; i < 10; i++) {
			room.pick(MINA.id(), 0, wrongPosition());
		}
		assertThat(outbox.ofType("error")).hasSize(10).allMatch(s -> pickError("too-early").equals(s.message()));
		int mark = outbox.mark();
		for (int i = 0; i < 5; i++) {
			room.chat(MINA.id(), "line " + i);
		}
		assertThat(outbox.since(mark)).extracting(Sent::type).containsOnly("chat").hasSize(5);

		scheduler.advance(Room.LEAD);
		room.pick(JOON.id(), 0, wrongPosition());
		for (int i = 0; i < 10; i++) {
			room.pick(JOON.id(), 0, wrongPosition());
		}
		mark = outbox.mark();
		for (int i = 0; i < 5; i++) {
			room.chat(JOON.id(), "talk " + i);
		}
		assertThat(outbox.since(mark)).extracting(Sent::type).containsOnly("chat").hasSize(5);
	}

	// ---- chat in a choice room (O4: never scores) ----

	@Test
	void anExactTitleByChatInAChoiceRoomIsJustAChatLine() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		String title = ChoiceFixture.title(currentAnimeIndex());
		toAudible(0);
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(msg("chat", "name", "Mina", "text", title));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
		// A player locked out of picks cannot win by chat either.
		room.pick(JOON.id(), 0, wrongPosition());
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertThat(outbox.since(mark)).extracting(Sent::type).containsExactly("chat");
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(JOON.id(), 0);
	}

	@Test
	void anExactTitleByChatBeforeStartAtGoesToEveryone() {
		// D-135: in a choice room chat is always shown to everyone; it never scores.
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		String title = ChoiceFixture.title(currentAnimeIndex());
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		readyAll(0);
		int playing = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark).stream().filter(s -> "chat".equals(s.type())).toList();
		assertThat(sent).hasSize(2).allMatch(Sent::toAll);
		assertThat(outbox.since(playing)).hasSize(1);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores().values()).containsOnly(0);
	}

	@Test
	void anExactTitleByChatFromAWatcherGoesToEveryone() {
		// D-135: in a choice room a watcher's chat is shown to everyone too.
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		String title = ChoiceFixture.title(currentAnimeIndex());
		room.addPlayer(HANA);
		toAudible(0);
		int mark = outbox.mark();
		room.chat(HANA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(msg("chat", "name", "Hana", "text", title));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aWrongChatInAChoiceRoomDoesNotLockThePick() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		int correct = correctPosition();
		toAudible(0);
		room.chat(MINA.id(), "not the title");
		assertThat(outbox.last("chat").toAll()).isTrue();
		room.pick(MINA.id(), 0, correct);
		assertThat(outbox.last("correct").message().get("name")).isEqualTo("Mina");
	}

	// ---- answer secrecy (A9 allow-list) ----

	@Test
	void noMessageBeforeTheRevealMarksTheCorrectChoice() {
		build(MINA, JOON);
		startChoice(DrawFilter.NONE);
		for (int index = 0; index < 5; index++) {
			int correct = correctPosition();
			if (index == 1) {
				room.addPlayer(HANA);
				room.chat(HANA.id(), "watching");
			}
			room.chat(MINA.id(), "early");
			readyAll(index);
			room.pick(MINA.id(), index, correct);
			scheduler.advance(Room.LEAD);
			room.pick(JOON.id(), index, correct == 0 ? 1 : 0);
			room.pick(JOON.id(), index, correct);
			room.pick(JOON.id(), index, 99);
			if (index % 2 == 0) {
				room.pick(MINA.id(), index, correct);
			}
			else {
				room.skip(MINA.id(), index);
				room.skip(JOON.id(), index);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		Set<String> seen = new TreeSet<>();
		List<String> prepareKeys = new ArrayList<>(PREPARE_KEYS);
		prepareKeys.add("choices");
		for (Sent sent : outbox.all()) {
			seen.add(sent.type());
			if ("reveal".equals(sent.type())) {
				continue;
			}
			String text = json(sent.message());
			assertThat(text).as(sent.type()).doesNotContain("correctChoice").doesNotContain("Choice Anime")
					.doesNotContain("Choice Reveal").doesNotContain("Choice Song");
			switch (sent.type()) {
				case "prepare" -> {
					assertThat(sent.message().keySet()).containsExactlyInAnyOrderElementsOf(prepareKeys);
					for (String url : coverUrls(sent.message())) {
						assertThat(url).matches(COVER_URL);
					}
				}
				case "start" -> assertThat(sent.message().keySet()).containsExactlyElementsOf(START_KEYS);
				case "pick-result" -> assertThat(sent.message().keySet()).containsExactly("type", "index", "choice");
				case "pickState" -> assertThat(sent.message().keySet()).containsExactlyInAnyOrder("type", "index",
						"picked", "total");
				case "wrong" -> assertThat(sent.message().keySet()).containsExactlyInAnyOrder("type", "index", "name");
				case "error" -> assertThat(sent.message().keySet()).containsExactly("type", "code", "for");
				default -> {
				}
			}
		}
		assertThat(seen).contains("settings", "prepare", "start", "pick-result", "pickState", "wrong", "error", "correct",
				"reveal", "chat", "skipState", "scores", "gameOver");
	}

}
