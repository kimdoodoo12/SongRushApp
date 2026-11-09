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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 101 (D-112) at Room level, on the cycle 036 fakes: the ranked game mode in a choice room
// (docs/cycles/101-game-modes.md item 4, with R1, R2, R5-R7). One pick per player per song; a correct
// pick ranks and scores like a correct ranked chat (3, 2, then 1 point) and sends correct{index, name,
// rank} to all with no chat line; a wrong pick sends pick-result to the sender only; the song ends
// once every connected Room player has picked, right or wrong (never with nobody connected). Chat
// never scores (cycle 100 O4).
// Production API as in RoomRankedTest's header: GameMode {FIRST, RANKED};
// Room.start(songCount, filter, AnswerMode.CHOICE, GameMode.RANKED); the reveal's ranking
// [{name, rank, points}] and winner = rank 1; Room.currentSong(playerId) adds choices and picked
// (cycle 100) and answered and correct [{name, rank}] (R5). The fixture is ChoiceFixture with 30
// covered anime; the correct position is found from the token in prepare.audioUrl.
// Review round 2 C2: a ranked prepare carries correct [{name, rank}] while the ranking is non-empty.
class RoomRankedChoiceTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final PlayerRef YUNA = new PlayerRef("p5", "Yuna");

	private static final List<String> PREPARE_KEYS = List.of("type", "index", "total", "audioUrl", "choices");

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
		built = ChoiceFixture.build(dir, ChoiceFixture.coveredSingles(0, 30));
		players = List.of(members);
		room = new Room(built.catalog(), tokens, scheduler, clock, new Random(100), outbox, players, built.covers());
	}

	private void startRankedChoice() {
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- helpers ----

	private Map<String, Object> lastPrepare() {
		return outbox.last("prepare").message();
	}

	private int currentAnimeIndex() {
		String url = (String) lastPrepare().get("audioUrl");
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
		assertThat(pos).isGreaterThanOrEqualTo(0);
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

	private void toAudible(int index) {
		readyAll(index);
		scheduler.advance(Room.LEAD);
	}

	private long deadlineAt() {
		return ((Number) outbox.last("start").message().get("deadlineAt")).longValue();
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

	private static Map<String, Object> correct(int index, String name, int rank) {
		return msg("correct", "index", index, "name", name, "rank", rank);
	}

	private static Map<String, Object> ranked(String name, int rank, int points) {
		return row("name", name, "rank", rank, "points", points);
	}

	private Map<String, Object> lastReveal() {
		return outbox.last("reveal").message();
	}

	// ---- picks ----

	@Test
	void correctPicksAreRankedWithThreeTwoOnePointsAndTheSongEndsWhenAllHavePicked() {
		build(MINA, JOON, HANA, SORA);
		startRankedChoice();
		int correct = correctPosition();
		int wrong = wrongPosition();
		toAudible(0);

		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(correct(0, "Mina", 1));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(JOON.id());
		assertThat(sent.get(0).message()).isEqualTo(msg("pick-result", "index", 0, "choice", wrong));

		mark = outbox.mark();
		room.pick(HANA.id(), 0, correct);
		sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::message).containsExactly(correct(0, "Hana", 2));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		mark = outbox.mark();
		room.pick(SORA.id(), 0, correct);
		sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("correct", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(correct(0, "Sora", 3));
		Map<String, Object> reveal = sent.get(1).message();
		assertThat(reveal.get("winner")).isEqualTo("Mina");
		assertThat(reveal.get("correctChoice")).isEqualTo(correct);
		assertThat(reveal.get("ranking"))
				.isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Hana", 2, 2), ranked("Sora", 3, 1)));
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 0).containsEntry(HANA.id(), 2)
				.containsEntry(SORA.id(), 1);
	}

	@Test
	void everyoneWrongEndsTheSongWithNoWinnerAndAnEmptyRanking() {
		build(MINA, JOON);
		startRankedChoice();
		int wrong = wrongPosition();
		toAudible(0);
		room.pick(MINA.id(), 0, wrong);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, wrong);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("pick-result", "reveal", "scores");
		assertThat(sent.get(0).to()).isEqualTo(JOON.id());
		assertThat(sent.get(1).message()).containsEntry("winner", null).containsEntry("ranking", List.of());
		assertThat(room.scores().values()).containsOnly(0);
	}

	@Test
	void aWrongPickLocksOutAndAnotherPlayersCorrectLocksNobody() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, wrongPosition());
		room.pick(MINA.id(), 0, correct);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		assertThat(outbox.since(mark)).extracting(Sent::message)
				.containsExactly(msg("error", "code", "already-picked", "for", "pick"));
		mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertThat(outbox.since(mark)).extracting(Sent::message)
				.containsExactly(msg("error", "code", "already-picked", "for", "pick"));
		assertThat(room.scores()).containsEntry(JOON.id(), 0).containsEntry(MINA.id(), 3);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// Hana can still pick after Mina's correct.
		mark = outbox.mark();
		room.pick(HANA.id(), 0, correct);
		assertThat(outbox.since(mark).get(0).message()).isEqualTo(correct(0, "Hana", 2));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void concurrentCorrectPicksAreRankedByArrival() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		room.pick(MINA.id(), 0, correct);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(correct(0, "Joon", 1),
				correct(0, "Mina", 2));
		assertThat(room.scores()).containsEntry(JOON.id(), 3).containsEntry(MINA.id(), 2);
	}

	@Test
	void aPickBeforeStartAtIsTooEarlyAndAWatcherPickSendsNothing() {
		build(MINA, JOON);
		startRankedChoice();
		int correct = correctPosition();
		room.addPlayer(YUNA);
		readyAll(0);
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		assertThat(outbox.since(mark)).extracting(Sent::message)
				.containsExactly(msg("error", "code", "too-early", "for", "pick"));
		scheduler.advance(Room.LEAD);
		mark = outbox.mark();
		room.pick(YUNA.id(), 0, correct);
		assertThat(outbox.since(mark)).isEmpty();
		room.pick(MINA.id(), 0, correct);
		room.pick(JOON.id(), 0, correct);
		assertThat(room.phase()).as("the watcher is not waited for").isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Joon", 2, 2)));
	}

	// ---- chat (cycle 100 O4) ----

	@Test
	void chatNeverScoresInARankedChoiceRoom() {
		build(MINA, JOON);
		startRankedChoice();
		String title = ChoiceFixture.title(currentAnimeIndex());
		int correct = correctPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(msg("chat", "name", "Mina", "text", title));
		room.pick(JOON.id(), 0, correct);
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertThat(outbox.since(mark)).extracting(Sent::type).containsExactly("chat");
		assertThat(outbox.since(mark).get(0).toAll()).isTrue();
		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 0).containsEntry(JOON.id(), 3);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", false).containsEntry("picked", null);
	}

	// ---- endings ----

	@Test
	void theLastUnpickedPlayerDisconnectingEndsTheSong() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(HANA.id(), 0, correct);
		room.pick(MINA.id(), 0, wrongPosition());
		int mark = outbox.mark();
		room.setConnected(JOON.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("reveal", "scores");
		assertThat(sent.get(0).message().get("winner")).isEqualTo("Hana");
		assertThat(sent.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Hana", 1, 3)));
	}

	@Test
	void theLastPlayerDisconnectingNeverEndsTheSong() {
		build(MINA, JOON);
		startRankedChoice();
		toAudible(0);
		room.pick(MINA.id(), 0, correctPosition());
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		assertThat(outbox.ofType("reveal")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// R7: a pick at or after the deadline ends the song with the ranking so far.
	@Test
	void aLatePickAtTheDeadlineEndsTheSongWithTheRankingSoFar() {
		build(MINA, JOON);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(JOON.id(), 0, correct);
		clock.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		int mark = outbox.mark();
		room.pick(MINA.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("reveal", "scores");
		assertThat(sent.get(0).message().get("winner")).isEqualTo("Joon");
		assertThat(sent.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Joon", 1, 3)));
		assertThat(sent.get(0).message().get("correctChoice")).isEqualTo(correct);
		assertThat(room.scores()).containsEntry(MINA.id(), 0);
	}

	@Test
	void theDeadlineAndASkipRevealHaveTheRankingAndKeepThePoints() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		toAudible(0);
		room.pick(HANA.id(), 0, correctPosition());
		scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Hana");
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Hana", 1, 3)));
		scheduler.advance(Room.REVEAL_PAUSE);

		toAudible(1);
		room.pick(JOON.id(), 1, correctPosition());
		room.skip(JOON.id(), 1);
		room.skip(MINA.id(), 1);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Joon");
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Joon", 1, 3)));
		assertThat(room.scores()).containsEntry(HANA.id(), 3).containsEntry(JOON.id(), 3).containsEntry(MINA.id(), 0);
	}

	// ---- snapshot and resume (R2, R5, R6) ----

	@Test
	void currentSongCarriesChoicesPickedAnsweredAndCorrect() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		List<String> urls = choices();
		int correct = correctPosition();
		int wrong = wrongPosition();
		Map<String, Object> inPrepare = room.currentSong(MINA.id());
		assertThat(inPrepare.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "choices", "picked",
				"answered", "correct");
		assertThat(inPrepare).containsEntry("picked", null).containsEntry("answered", false)
				.containsEntry("correct", List.of());

		toAudible(0);
		room.pick(MINA.id(), 0, wrong);
		room.pick(JOON.id(), 0, correct);
		List<Map<String, Object>> names = List.of(row("name", "Joon", "rank", 1));
		Map<String, Object> mina = room.currentSong(MINA.id());
		assertThat(mina).containsEntry("picked", wrong).containsEntry("answered", false).containsEntry("correct", names);
		Map<String, Object> joon = room.currentSong(JOON.id());
		assertThat(joon).containsEntry("picked", correct).containsEntry("answered", true).containsEntry("correct", names);
		assertThat(room.currentSong(HANA.id())).containsEntry("picked", null).containsEntry("answered", false);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) joon.get("choices");
		assertThat(rows).extracting(r -> r.get("coverUrl")).containsExactlyElementsOf(urls);
	}

	@Test
	void aResumeKeepsPicksAndRanksAndASongWhoseConnectedPlayersAllPickedEndsWhenItStarts() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		int correct = correctPosition();
		toAudible(0);
		room.pick(MINA.id(), 0, correct);
		room.pick(JOON.id(), 0, wrongPosition());
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.setConnected(HANA.id(), false);
		room.pause();
		assertThat(outbox.ofType("reveal")).isEmpty();

		room.setConnected(MINA.id(), true);
		room.resume();
		room.setConnected(JOON.id(), true);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.currentSong(MINA.id())).containsEntry("picked", correct).containsEntry("answered", true);
		assertThat(room.currentSong(JOON.id())).containsEntry("answered", false);
		room.ready(MINA.id(), 0);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Mina");
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3)));
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 0).containsEntry(HANA.id(), 0);
	}

	// review round 2 C2: in a ranked choice room the resume's re-prepare carries choices and the
	// correct names; the first prepare (empty ranking) has no correct key.
	@Test
	void aResumesRePrepareCarriesTheCorrectNamesAndTheFirstPrepareDoesNot() {
		build(MINA, JOON);
		startRankedChoice();
		assertThat(lastPrepare().keySet()).containsExactlyInAnyOrderElementsOf(PREPARE_KEYS);
		List<String> urls = choices();
		toAudible(0);
		room.pick(JOON.id(), 0, correctPosition());
		room.setConnected(JOON.id(), false);
		room.setConnected(MINA.id(), false);
		room.pause();
		int mark = outbox.mark();
		room.setConnected(JOON.id(), true);
		room.resume();
		List<Sent> prepares = outbox.since(mark).stream().filter(s -> "prepare".equals(s.type())).toList();
		assertThat(prepares).hasSize(1);
		Map<String, Object> prepare = prepares.get(0).message();
		List<String> keys = new ArrayList<>(PREPARE_KEYS);
		keys.add("correct");
		assertThat(prepare.keySet()).containsExactlyInAnyOrderElementsOf(keys);
		assertThat(prepare).containsEntry("correct", List.of(row("name", "Joon", "rank", 1)));
		assertThat(choices()).isEqualTo(urls);
		assertThat(MAPPER.writeValueAsString(prepare)).doesNotContain("correctChoice");
	}

	@Test
	void ranksStartOverOnTheNextSong() {
		build(MINA, JOON);
		startRankedChoice();
		toAudible(0);
		room.pick(MINA.id(), 0, correctPosition());
		room.pick(JOON.id(), 0, correctPosition());
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.songIndex()).isEqualTo(1);
		toAudible(1);
		int mark = outbox.mark();
		room.pick(JOON.id(), 1, correctPosition());
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(correct(1, "Joon", 1));
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 2 + 3);
	}

	// ---- first come in a choice room is unchanged ----

	@Test
	void aFirstComeChoiceRoomStillEndsOnTheFirstCorrectPick() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHOICE, GameMode.FIRST);
		int correct = correctPosition();
		toAudible(0);
		int mark = outbox.mark();
		room.pick(JOON.id(), 0, correct);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("correct", "reveal", "scores");
		assertThat(sent.get(0).message()).isEqualTo(msg("correct", "index", 0, "name", "Joon"));
		assertThat(sent.get(1).message()).doesNotContainKey("ranking");
		assertThat(room.scores()).containsEntry(JOON.id(), 1);
		assertThat(room.currentSong(MINA.id())).isNull();
	}

	// ---- secrecy ----

	@Test
	void nothingBeforeTheRevealMarksTheCorrectChoiceOrNamesTheSong() {
		build(MINA, JOON, HANA);
		startRankedChoice();
		for (int index = 0; index < 5; index++) {
			int correct = correctPosition();
			room.chat(MINA.id(), ChoiceFixture.title(currentAnimeIndex()));
			readyAll(index);
			scheduler.advance(Room.LEAD);
			room.pick(JOON.id(), index, correct == 0 ? 1 : 0);
			room.pick(MINA.id(), index, correct);
			room.pick(HANA.id(), index, correct);
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		for (Sent sent : outbox.all()) {
			if ("reveal".equals(sent.type())) {
				continue;
			}
			String text = MAPPER.writeValueAsString(sent.message());
			assertThat(text).as(sent.type()).doesNotContain("correctChoice").doesNotContain("Choice Reveal")
					.doesNotContain("Choice Song");
			if (text.contains("Choice Anime")) {
				assertThat(sent.type()).isEqualTo("chat");
				assertThat(sent.to()).as("the early title went to its sender only").isEqualTo(MINA.id());
			}
			switch (sent.type()) {
				case "correct" -> assertThat(sent.message().keySet()).containsExactly("type", "index", "name", "rank");
				case "prepare" -> assertThat(sent.message().keySet()).containsExactlyInAnyOrderElementsOf(PREPARE_KEYS);
				case "pick-result" -> assertThat(sent.message().keySet()).containsExactly("type", "index", "choice");
				default -> {
				}
			}
		}
	}

}
