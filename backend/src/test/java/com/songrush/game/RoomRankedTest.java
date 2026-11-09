package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// Cycle 101 (D-112) at Room level, on the cycle 036 fakes: the ranked game mode in a chat room
// (docs/cycles/101-game-modes.md items 1, 3, 5-8, with the review amendments R1-R9).
//
// Production API relied on (same pattern as AnswerMode, cycle 100 L3):
// - enum GameMode {FIRST, RANKED}, wire values "first" and "ranked" (wire(), fromWire(String)).
// - Room.start(songCount, filter, answerMode, gameMode); the older overloads are FIRST.
// - settings carries gameMode:"ranked" (left out for first); it is sent when the filter is non-empty,
//   the answer mode is choice or the game mode is ranked.
// - Ranked correct is {type, index, name, rank}; first-come correct stays {type, index, name}.
//   A correct ranked chat sends correct to all, then chat{name, text} to the sender only, with no
//   frame in between. Points by rank: 3, 2, then 1.
// - The ranked reveal adds ranking: [{name, rank, points}] in rank order (empty when nobody
//   answered), and winner is the rank-1 name or null, on every ending (R7).
// - Room.currentSong(playerId) in ranked mode adds answered (bool, that player answered correctly)
//   and correct: [{name, rank}] in rank order; currentSong() is unchanged.
// The fixture is 30 covered anime, each its own group with one OP of 60 s (ChoiceFixture); the
// current song is found by resolving the token in prepare.audioUrl, and its only title is
// ChoiceFixture.title(a).
class RoomRankedTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final PlayerRef SORA = new PlayerRef("p4", "Sora");

	private static final PlayerRef YUNA = new PlayerRef("p5", "Yuna");

	private static final List<String> PREPARE_KEYS = List.of("type", "index", "total", "audioUrl");

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

	private void startRanked() {
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.RANKED);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	// ---- helpers ----

	private int currentAnimeIndex() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return built.animeIndexOfTheme(themeId);
	}

	private String title() {
		return ChoiceFixture.title(currentAnimeIndex());
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

	private static Map<String, Object> chat(String name, String text) {
		return msg("chat", "name", name, "text", text);
	}

	private static Map<String, Object> ranked(String name, int rank, int points) {
		return row("name", name, "rank", rank, "points", points);
	}

	// A correct ranked chat: correct to all, then the text to the sender only, and nothing else.
	private void assertRankedCorrect(List<Sent> sent, PlayerRef sender, int index, int rank, String text) {
		assertThat(sent).as("frames of " + sender.name() + "'s correct chat").hasSizeGreaterThanOrEqualTo(2);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(correct(index, sender.name(), rank));
		assertThat(sent.get(1).to()).isEqualTo(sender.id());
		assertThat(sent.get(1).message()).isEqualTo(chat(sender.name(), text));
	}

	private Map<String, Object> lastReveal() {
		return outbox.last("reveal").message();
	}

	// ---- settings ----

	@Test
	void aRankedStartSendsSettingsWithTheGameModeBeforeThePrepare() {
		build(MINA, JOON);
		startRanked();
		List<Sent> all = outbox.all();
		assertThat(all.get(0).toAll()).isTrue();
		assertThat(all.get(0).message()).isEqualTo(msg("settings", "gameMode", "ranked"));
		assertThat(all.get(1).type()).isEqualTo("prepare");
		assertThat(all.get(1).message().keySet()).containsExactlyInAnyOrderElementsOf(PREPARE_KEYS);
		assertThat(outbox.ofType("settings")).hasSize(1);
		assertThat(room.settingsMessage()).isEqualTo(msg("settings", "gameMode", "ranked"));
	}

	@Test
	void theGameModeJoinsTheFilterAndTheAnswerModeInOneSettings() {
		build(MINA);
		room.start(5, new DrawFilter(Set.of("OP"), null, null, DrawFilter.HARD), AnswerMode.CHOICE, GameMode.RANKED);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("settings")).hasSize(1);
		assertThat(outbox.last("settings").message()).isEqualTo(
				msg("settings", "types", List.of("OP"), "tier", "hard", "answerMode", "choice", "gameMode", "ranked"));
	}

	@Test
	void aFirstComeGameNeverSendsTheGameMode() {
		for (int run = 0; run < 4; run++) {
			RecordingOutbox out = new RecordingOutbox();
			Built b = ChoiceFixture.build(dir.resolve("first" + run), ChoiceFixture.coveredSingles(0, 30));
			Room r = new Room(b.catalog(), tokens, scheduler, clock, new Random(run), out, List.of(MINA), b.covers());
			switch (run) {
				case 0 -> r.start(5);
				case 1 -> r.start(5, DrawFilter.NONE);
				case 2 -> r.start(5, DrawFilter.NONE, AnswerMode.CHAT);
				default -> r.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
			}
			assertThat(r.phase()).as("run " + run).isEqualTo(Room.Phase.PREPARE);
			assertThat(out.ofType("settings")).as("run " + run).isEmpty();
			assertThat(r.settingsMessage()).as("run " + run).isNull();
		}
		RecordingOutbox out = new RecordingOutbox();
		Built b = ChoiceFixture.build(dir.resolve("filtered"), ChoiceFixture.coveredSingles(0, 30));
		Room r = new Room(b.catalog(), tokens, scheduler, clock, new Random(9), out, List.of(MINA), b.covers());
		r.start(5, new DrawFilter(Set.of("OP"), null, null, null), AnswerMode.CHOICE, GameMode.FIRST);
		assertThat(out.last("settings").message()).isEqualTo(msg("settings", "types", List.of("OP"), "answerMode", "choice"));
	}

	@Test
	void theGameModeWireValues() {
		assertThat(GameMode.FIRST.wire()).isEqualTo("first");
		assertThat(GameMode.RANKED.wire()).isEqualTo("ranked");
		assertThat(GameMode.fromWire("first")).isEqualTo(GameMode.FIRST);
		assertThat(GameMode.fromWire("ranked")).isEqualTo(GameMode.RANKED);
		for (String bad : new String[] {"Ranked", "RANKED", "FIRST", "", " ranked", "first-come", null}) {
			assertThat(GameMode.fromWire(bad)).as(String.valueOf(bad)).isNull();
		}
	}

	// ---- ranks and points ----

	@Test
	void ranksOneTwoThreeGetThreeTwoOneAndAFourthCorrectPlayerGetsOne() {
		build(MINA, JOON, HANA, SORA);
		startRanked();
		String title = title();
		toAudible(0);

		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertRankedCorrect(sent, MINA, 0, 1, title);
		assertThat(sent).hasSize(2);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		mark = outbox.mark();
		room.chat(JOON.id(), title);
		sent = outbox.since(mark);
		assertRankedCorrect(sent, JOON, 0, 2, title);
		assertThat(sent).hasSize(2);

		mark = outbox.mark();
		room.chat(HANA.id(), title);
		sent = outbox.since(mark);
		assertRankedCorrect(sent, HANA, 0, 3, title);
		assertThat(sent).hasSize(2);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 2).containsEntry(HANA.id(), 1)
				.containsEntry(SORA.id(), 0);

		// The last one: the song ends.
		mark = outbox.mark();
		room.chat(SORA.id(), title);
		sent = outbox.since(mark);
		assertRankedCorrect(sent, SORA, 0, 4, title);
		assertThat(sent).extracting(Sent::type).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent.get(2).toAll()).isTrue();
		assertThat(sent.get(3).toAll()).isTrue();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 2).containsEntry(HANA.id(), 1)
				.containsEntry(SORA.id(), 1);

		Map<String, Object> reveal = sent.get(2).message();
		assertThat(reveal.get("winner")).isEqualTo("Mina");
		assertThat(reveal.get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Joon", 2, 2),
				ranked("Hana", 3, 1), ranked("Sora", 4, 1)));
		assertThat(sent.get(3).message()).isEqualTo(msg("scores", "scores", List.of(row("name", "Mina", "score", 3),
				row("name", "Joon", "score", 2), row("name", "Hana", "score", 1), row("name", "Sora", "score", 1))));
	}

	@Test
	void theSongContinuesUntilEveryConnectedPlayerHasAnswered() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(JOON.id(), title);
		room.chat(MINA.id(), "not it");
		room.chat(HANA.id(), title);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("reveal")).isEmpty();
		room.chat(MINA.id(), title);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Joon");
		assertThat(lastReveal().get("ranking"))
				.isEqualTo(List.of(ranked("Joon", 1, 3), ranked("Hana", 2, 2), ranked("Mina", 3, 1)));
	}

	@Test
	void aRepeatedCorrectChatFromAnAnsweredPlayerIsAnOrdinaryChatLine() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		room.chat(MINA.id(), title.toUpperCase());
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(2).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(chat("Mina", title));
		assertThat(sent.get(1).message()).isEqualTo(chat("Mina", title.toUpperCase()));
		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 3);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		// The other player still ranks second.
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertRankedCorrect(outbox.since(mark), JOON, 0, 2, title);
		assertThat(room.scores()).containsEntry(JOON.id(), 2);
	}

	@Test
	void aWrongChatIsBroadcastAndScoresNothing() {
		build(MINA, JOON);
		startRanked();
		toAudible(0);
		int mark = outbox.mark();
		room.chat(MINA.id(), "Choice Anime 999");
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).toAll()).isTrue();
		assertThat(sent.get(0).message()).isEqualTo(chat("Mina", "Choice Anime 999"));
		assertThat(room.scores().values()).containsOnly(0);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", false);
	}

	@Test
	void anExactTitleBeforeStartAtGoesToTheSenderOnlyAndScoresNothing() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		readyAll(0);
		int playing = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> chats = outbox.since(mark).stream().filter(s -> "chat".equals(s.type())).toList();
		assertThat(chats).extracting(Sent::to).containsExactly(MINA.id(), JOON.id());
		assertThat(outbox.since(playing)).hasSize(1);
		assertThat(outbox.ofType("correct")).isEmpty();
		assertThat(room.scores().values()).containsOnly(0);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", false);

		// After startAt the same players can still answer.
		scheduler.advance(Room.LEAD);
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertRankedCorrect(outbox.since(mark), JOON, 0, 1, title);
	}

	@Test
	void aWatcherIsNeverRankedAndIsNotWaitedFor() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		room.addPlayer(YUNA);
		toAudible(0);
		int mark = outbox.mark();
		room.chat(YUNA.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).to()).isEqualTo(YUNA.id());
		assertThat(sent.get(0).message()).isEqualTo(chat("Yuna", title));
		assertThat(room.currentSong(YUNA.id())).containsEntry("answered", false);

		room.chat(MINA.id(), title);
		room.chat(JOON.id(), title);
		assertThat(room.phase()).as("the watcher is not waited for").isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Joon", 2, 2)));
		assertThat(outbox.ofType("correct")).extracting(s -> s.message().get("name")).containsExactly("Mina", "Joon");
	}

	@Test
	void simultaneousCorrectChatsAreRankedByArrival() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		room.chat(MINA.id(), title);
		List<Sent> corrects = outbox.since(mark).stream().filter(s -> "correct".equals(s.type())).toList();
		assertThat(corrects).extracting(Sent::message).containsExactly(correct(0, "Joon", 1), correct(0, "Mina", 2));
		assertThat(room.scores()).containsEntry(JOON.id(), 3).containsEntry(MINA.id(), 2);
	}

	@Test
	void ranksAndAnsweredStartOverOnTheNextSong() {
		build(MINA, JOON);
		startRanked();
		String first = title();
		toAudible(0);
		room.chat(MINA.id(), first);
		room.chat(JOON.id(), first);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", false).containsEntry("correct", List.of());

		String second = title();
		toAudible(1);
		int mark = outbox.mark();
		room.chat(JOON.id(), second);
		assertRankedCorrect(outbox.since(mark), JOON, 1, 1, second);
		// Song 0's title is not this song's.
		room.chat(MINA.id(), first);
		assertThat(outbox.last("chat").toAll()).isTrue();
		room.chat(MINA.id(), second);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Joon");
		assertThat(room.scores()).containsEntry(MINA.id(), 3 + 2).containsEntry(JOON.id(), 2 + 3);
	}

	// ---- endings ----

	@Test
	void theDeadlineEndsTheSongWithAPartialRanking() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(HANA.id(), title);
		room.chat(MINA.id(), title);
		int mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).startsWith("reveal", "scores");
		Map<String, Object> reveal = sent.get(0).message();
		assertThat(reveal.get("winner")).isEqualTo("Hana");
		assertThat(reveal.get("ranking")).isEqualTo(List.of(ranked("Hana", 1, 3), ranked("Mina", 2, 2)));
		assertThat(room.scores()).containsEntry(HANA.id(), 3).containsEntry(MINA.id(), 2).containsEntry(JOON.id(), 0);
	}

	@Test
	void theDeadlineWithNoCorrectAnswerHasAnEmptyRanking() {
		build(MINA, JOON);
		startRanked();
		toAudible(0);
		scheduler.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal()).containsEntry("winner", null).containsEntry("ranking", List.of());
	}

	// R7: a chat at or after the deadline, before the deadline task runs, ends the song with the
	// ranking so far and never ranks that chat.
	@Test
	void aLateCorrectChatAtTheDeadlineEndsTheSongWithTheRankingSoFar() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		clock.advance(Duration.ofMillis(deadlineAt() - clock.millis()));
		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).doesNotContain("correct");
		assertThat(sent.get(0).type()).isEqualTo("reveal");
		assertThat(sent.get(0).message().get("winner")).isEqualTo("Mina");
		assertThat(sent.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3)));
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 0);
	}

	// Item 7 and R8: an answered player may vote, and the points stay.
	@Test
	void aSkipKeepsThePointsAndAnAnsweredPlayerMayVote() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(JOON.id(), title);
		room.skip(JOON.id(), 0);
		assertThat(outbox.last("skipState").message()).isEqualTo(msg("skipState", "index", 0, "votes", 1, "needed", 2));
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("skipState", "reveal", "scores");
		assertThat(sent.get(1).message().get("winner")).isEqualTo("Joon");
		assertThat(sent.get(1).message().get("ranking")).isEqualTo(List.of(ranked("Joon", 1, 3)));
		assertThat(room.scores()).containsEntry(JOON.id(), 3).containsEntry(MINA.id(), 0).containsEntry(HANA.id(), 0);
	}

	@Test
	void aSkipWithNoCorrectAnswerHasAnEmptyRanking() {
		build(MINA, JOON);
		startRanked();
		toAudible(0);
		room.skip(MINA.id(), 0);
		room.skip(JOON.id(), 0);
		assertThat(lastReveal()).containsEntry("winner", null).containsEntry("ranking", List.of());
	}

	// ---- connection changes (item 6, R1, R2, R6) ----

	@Test
	void aDisconnectOfTheLastUnansweredPlayerEndsTheSong() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(JOON.id(), title);
		room.chat(MINA.id(), title);
		int mark = outbox.mark();
		room.setConnected(HANA.id(), false);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("reveal", "scores");
		assertThat(sent.get(0).message().get("winner")).isEqualTo("Joon");
		assertThat(sent.get(0).message().get("ranking")).isEqualTo(List.of(ranked("Joon", 1, 3), ranked("Mina", 2, 2)));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	@Test
	void aDisconnectOfAnAnsweredPlayerDoesNotEndTheSongWhileAnotherHasNotAnswered() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("reveal")).isEmpty();
		room.chat(HANA.id(), title);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Hana", 2, 2)));
	}

	// R1 and the added acceptance: A answered then left, B unanswered then left: no reveal, and B's
	// return replays the same song.
	@Test
	void theLastPlayerDisconnectingNeverEndsTheSongAndTheReturnReplaysIt() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		int mark = outbox.mark();
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		assertThat(outbox.since(mark)).extracting(Sent::type).doesNotContain("reveal", "scores");
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);

		room.setConnected(JOON.id(), true);
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(0);
		assertThat(title()).as("the same song is prepared again").isEqualTo(title);
		assertThat(outbox.since(mark)).extracting(Sent::type).doesNotContain("reveal", "scores");
		assertThat(room.currentSong(JOON.id())).containsEntry("answered", false)
				.containsEntry("correct", List.of(row("name", "Mina", "rank", 1)));

		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertRankedCorrect(sent, JOON, 0, 2, title);
		assertThat(sent).extracting(Sent::type).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent.get(2).message().get("winner")).isEqualTo("Mina");
		assertThat(sent.get(2).message().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3), ranked("Joon", 2, 2)));
	}

	// R2: the answered set and the ranking survive a resume's re-prepare.
	@Test
	void answeredStateAndRanksSurviveAResume() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		room.pause();
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.currentSong(MINA.id())).containsEntry("answered", true)
				.containsEntry("correct", List.of(row("name", "Mina", "rank", 1)));
		toAudible(0);
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(chat("Mina", title));
		assertThat(outbox.since(mark)).allMatch(Sent::toAll);
		mark = outbox.mark();
		room.chat(JOON.id(), title);
		assertRankedCorrect(outbox.since(mark), JOON, 0, 2, title);
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 2);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	// Added acceptance: A answers, everyone leaves, both return; A's repeated correct chat after the
	// new startAt earns nothing.
	@Test
	void aRepeatedCorrectChatAfterEveryoneLeftAndReturnedEarnsNothing() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		room.setConnected(MINA.id(), true);
		room.resume();
		room.setConnected(JOON.id(), true);
		toAudible(0);
		int mark = outbox.mark();
		room.chat(MINA.id(), title);
		assertThat(outbox.since(mark)).extracting(Sent::message).containsExactly(chat("Mina", title));
		assertThat(outbox.ofType("correct")).hasSize(1);
		assertThat(room.scores()).containsEntry(MINA.id(), 3);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// R6: a resumed song whose connected players have all answered ends when it starts, with the
	// ranking it had and no new points.
	@Test
	void aResumedSongWhoseConnectedPlayersAllAnsweredEndsWhenItStarts() {
		build(MINA, JOON);
		startRanked();
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		room.setConnected(MINA.id(), false);
		room.setConnected(JOON.id(), false);
		room.pause();
		room.setConnected(MINA.id(), true);
		room.resume();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("reveal")).isEmpty();
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(lastReveal().get("winner")).isEqualTo("Mina");
		assertThat(lastReveal().get("ranking")).isEqualTo(List.of(ranked("Mina", 1, 3)));
		assertThat(room.scores()).containsEntry(MINA.id(), 3).containsEntry(JOON.id(), 0);
	}

	// ---- reconnect snapshot (R5) ----

	@Test
	void currentSongCarriesAnsweredAndTheCorrectNamesInRankedMode() {
		build(MINA, JOON, HANA);
		startRanked();
		String title = title();
		Map<String, Object> inPrepare = room.currentSong(MINA.id());
		assertThat(inPrepare.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "answered", "correct");
		assertThat(inPrepare).containsEntry("answered", false).containsEntry("correct", List.of());

		toAudible(0);
		room.chat(JOON.id(), title);
		room.chat(MINA.id(), title);
		room.addPlayer(YUNA);
		Map<String, Object> mina = room.currentSong(MINA.id());
		assertThat(mina.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt",
				"answered", "correct");
		List<Map<String, Object>> names = List.of(row("name", "Joon", "rank", 1), row("name", "Mina", "rank", 2));
		assertThat(mina).containsEntry("answered", true).containsEntry("correct", names);
		assertThat(room.currentSong(JOON.id())).containsEntry("answered", true).containsEntry("correct", names);
		assertThat(room.currentSong(HANA.id())).containsEntry("answered", false).containsEntry("correct", names);
		assertThat(room.currentSong(YUNA.id())).containsEntry("answered", false).containsEntry("correct", names);
		// The no-argument form stays what it was.
		assertThat(room.currentSong()).doesNotContainKeys("answered", "correct");
	}

	@Test
	void aFirstComeCurrentSongHasNoAnsweredOrCorrect() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		toAudible(0);
		assertThat(room.currentSong(MINA.id())).isEqualTo(room.currentSong());
		assertThat(room.currentSong(MINA.id())).doesNotContainKeys("answered", "correct");
	}

	// ---- first come (item 2) ----

	@Test
	void firstComeIsUnchanged() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		String title = title();
		toAudible(0);
		int mark = outbox.mark();
		room.chat(JOON.id(), title);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::type).containsExactly("correct", "chat", "reveal", "scores");
		assertThat(sent).allMatch(Sent::toAll);
		assertThat(sent.get(0).message()).isEqualTo(msg("correct", "index", 0, "name", "Joon"));
		assertThat(sent.get(1).message()).isEqualTo(chat("Joon", title));
		assertThat(sent.get(2).message().get("winner")).isEqualTo("Joon");
		assertThat(sent.get(2).message()).doesNotContainKey("ranking");
		assertThat(room.scores()).containsEntry(JOON.id(), 1).containsEntry(MINA.id(), 0);
	}

	@Test
	void aRankedRoomsNextGameStartedWithoutAModeIsFirstCome() {
		build(MINA);
		startRanked();
		for (int index = 0; index < 5; index++) {
			readyAll(index);
			room.skip(MINA.id(), index);
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		int mark = outbox.mark();
		room.start(5);
		assertThat(outbox.since(mark)).extracting(Sent::type).doesNotContain("settings");
		String title = title();
		toAudible(0);
		room.chat(MINA.id(), title);
		assertThat(outbox.last("correct").message()).isEqualTo(msg("correct", "index", 0, "name", "Mina"));
		assertThat(lastReveal()).doesNotContainKey("ranking");
		assertThat(room.scores()).containsEntry(MINA.id(), 1);
	}

	// ---- secrecy (item 8) ----

	@Test
	void noPreRevealMessageButTheSendersOwnEchoCarriesTheCorrectText() {
		build(MINA, JOON, HANA);
		startRanked();
		Set<String> correctKeysSeen = new HashSet<>();
		for (int index = 0; index < 5; index++) {
			String title = title();
			if (index == 1) {
				room.addPlayer(YUNA);
				room.chat(YUNA.id(), title);
			}
			room.chat(MINA.id(), title);
			readyAll(index);
			scheduler.advance(Room.LEAD);
			room.chat(JOON.id(), "a guess");
			room.chat(JOON.id(), title);
			room.chat(HANA.id(), title);
			if (index >= 2) {
				room.chat(YUNA.id(), title);
			}
			if (index % 2 == 0) {
				room.chat(MINA.id(), title);
			}
			else {
				// Answered players may vote too (R8); three votes are a majority of three or four.
				room.skip(MINA.id(), index);
				room.skip(HANA.id(), index);
				room.skip(JOON.id(), index);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
			scheduler.advance(Room.REVEAL_PAUSE);
			// Promoted at the next prepare; she is a Room player from then on and readies with the rest.
			if (index == 1) {
				players = List.of(MINA, JOON, HANA, YUNA);
			}
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		for (Sent sent : outbox.all()) {
			if ("reveal".equals(sent.type())) {
				continue;
			}
			String text = MAPPER.writeValueAsString(sent.message());
			assertThat(text).as(sent.type()).doesNotContain("Choice Reveal").doesNotContain("Choice Song");
			if (text.contains("Choice Anime")) {
				assertThat(sent.type()).as(text).isEqualTo("chat");
				assertThat(sent.toAll()).as("a title reaches only its sender: " + text).isFalse();
				assertThat(sent.message().get("name")).isEqualTo(nameOf(sent.to()));
			}
			if ("correct".equals(sent.type())) {
				correctKeysSeen.addAll(sent.message().keySet());
				assertThat(sent.message().keySet()).containsExactly("type", "index", "name", "rank");
			}
		}
		assertThat(correctKeysSeen).isNotEmpty();
	}

	private String nameOf(String playerId) {
		for (PlayerRef p : List.of(MINA, JOON, HANA, SORA, YUNA)) {
			if (p.id().equals(playerId)) {
				return p.name();
			}
		}
		return null;
	}

	@Test
	void firstComeCorrectKeepsExactlyItsKeys() {
		build(MINA, JOON);
		room.start(5, DrawFilter.NONE, AnswerMode.CHAT, GameMode.FIRST);
		List<String> titles = new ArrayList<>();
		for (int index = 0; index < 5; index++) {
			titles.add(title());
			toAudible(index);
			room.chat(index % 2 == 0 ? MINA.id() : JOON.id(), titles.get(index));
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(outbox.ofType("correct")).hasSize(5)
				.allSatisfy(s -> assertThat(s.message().keySet()).containsExactly("type", "index", "name"));
	}

}
