package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 044 (B-005c) criterion 1: Room.pause(), Room.resume() and Room.paused() on the cycle 036
// fakes (fake clock, manual scheduler, seeded Random, recording Outbox, real AudioTokens). The
// current song is found by resolving the token in prepare.audioUrl (docs/cycles/044-pause-empty.md).
class RoomPauseTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	// Far past any deadline, prepare timeout or reveal pause of the fixture songs.
	private static final Duration LONG = Duration.ofMinutes(10);

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private void build(GameScheduler gameScheduler) {
		catalog = GameFixture.build(dir, SEVEN, 0);
		room = new Room(catalog, tokens, gameScheduler, clock, new Random(44), outbox, List.of(MINA, JOON));
	}

	private void build() {
		build(scheduler);
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

	private Map<String, Object> lastPrepare() {
		return outbox.last("prepare").message();
	}

	private Map<String, Object> lastStart() {
		return outbox.last("start").message();
	}

	private Theme currentTheme() {
		String url = (String) lastPrepare().get("audioUrl");
		assertThat(url).startsWith("/audio/");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		return catalog.theme(themeId).orElseThrow();
	}

	private Anime currentAnime() {
		return catalog.anime(currentTheme().animeId()).orElseThrow();
	}

	private String exactTitle() {
		return currentAnime().titles().get("en").get(0);
	}

	private void readyAll(int index) {
		room.ready(MINA.id(), index);
		room.ready(JOON.id(), index);
	}

	// From LOBBY: start a 5-song game and play song 0 until PLAYING.
	private void startAndPlay() {
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	// From PLAYING: waits for startAt and lets the player answer with the exact title.
	private void answer(PlayerRef winner) {
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		long left = num(lastStart(), "startAt") - clock.millis();
		if (left > 0) {
			scheduler.advance(Duration.ofMillis(left));
		}
		int mark = outbox.mark();
		room.chat(winner.id(), exactTitle());
		assertThat(types(outbox.since(mark))).as("answered").contains("correct");
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

	private static List<String> scoreRows(Map<String, Object> scoresMessage) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<?>) scoresMessage.get("scores")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) row;
			rows.add(r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	private static List<String> rankingRows(Map<String, Object> gameOver) {
		List<String> rows = new ArrayList<>();
		for (Object row : (List<?>) gameOver.get("ranking")) {
			@SuppressWarnings("unchecked")
			Map<String, Object> r = (Map<String, Object>) row;
			rows.add(num(r, "rank") + ":" + r.get("name") + ":" + num(r, "score"));
		}
		return rows;
	}

	// ---- pause in PLAYING ----

	@Test
	void pauseInPlayingStopsTheSongAndResumeReprepareTheSameIndexWithANewAudioUrl() {
		build();
		startAndPlay();
		Map<String, Object> firstPrepare = lastPrepare();
		Map<String, Object> firstStart = lastStart();
		Theme theme = currentTheme();
		scheduler.advance(Room.LEAD.plusSeconds(5));

		assertThat(room.paused()).isFalse();
		int mark = outbox.mark();
		room.pause();
		assertThat(room.paused()).isTrue();
		assertThat(outbox.since(mark)).as("pause sends nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isZero();

		long pastDeadline = num(firstStart, "deadlineAt") - clock.millis();
		scheduler.advance(Duration.ofMillis(pastDeadline));
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).as("no deadline, reveal or prepare while paused").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.songIndex()).isZero();
		assertThat(room.paused()).isTrue();

		room.resume();
		assertThat(room.paused()).isFalse();
		List<Sent> resumed = outbox.since(mark);
		assertThat(types(resumed)).containsExactly("prepare");
		assertThat(resumed.get(0).toAll()).isTrue();
		Map<String, Object> prepare = resumed.get(0).message();
		assertThat(num(prepare, "index")).isZero();
		assertThat(num(prepare, "total")).isEqualTo(5);
		assertThat(prepare.get("audioUrl")).as("a new audio token").isNotEqualTo(firstPrepare.get("audioUrl"));
		assertThat(currentTheme().id()).as("the same song").isEqualTo(theme.id());
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isZero();

		mark = outbox.mark();
		long readyAt = clock.millis();
		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		List<Sent> started = outbox.since(mark);
		assertThat(types(started)).containsExactly("start");
		Map<String, Object> start = started.get(0).message();
		assertThat(num(start, "index")).isZero();
		assertThat(num(start, "serverTime")).isEqualTo(readyAt);
		assertThat(num(start, "startAt")).as("a fresh startAt").isEqualTo(readyAt + Room.LEAD.toMillis())
				.isGreaterThan(num(firstStart, "startAt"));
		assertThat(num(start, "deadlineAt") - num(start, "startAt"))
				.isEqualTo(num(firstStart, "deadlineAt") - num(firstStart, "startAt"));

		// The new deadline runs as usual.
		mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(num(start, "deadlineAt") - clock.millis()));
		assertThat(types(outbox.since(mark))).containsExactly("reveal", "scores");
		assertThat(num(outbox.last("reveal").message(), "index")).isZero();
	}

	// ---- pause in PREPARE ----

	@Test
	void pauseInPrepareStopsThePrepareTimeoutAndResumeReprepareTheSameIndex() {
		build();
		room.start(5);
		Map<String, Object> firstPrepare = lastPrepare();
		Theme theme = currentTheme();
		room.ready(MINA.id(), 0);
		scheduler.advance(Duration.ofSeconds(10));

		int mark = outbox.mark();
		room.pause();
		assertThat(room.paused()).isTrue();
		scheduler.advance(Room.PREPARE_TIMEOUT);
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).as("the prepare timeout does not fire while paused").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isZero();

		room.resume();
		assertThat(room.paused()).isFalse();
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		Map<String, Object> prepare = lastPrepare();
		assertThat(num(prepare, "index")).isZero();
		assertThat(prepare.get("audioUrl")).isNotEqualTo(firstPrepare.get("audioUrl"));
		assertThat(currentTheme().id()).isEqualTo(theme.id());

		// The ready set was cleared: Joon alone does not start the song.
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(outbox.ofType("start")).isEmpty();

		// A fresh prepare timeout, counted from the resume.
		mark = outbox.mark();
		scheduler.advance(Room.PREPARE_TIMEOUT.minusMillis(1));
		assertThat(outbox.since(mark)).isEmpty();
		scheduler.advance(Duration.ofMillis(1));
		assertThat(types(outbox.since(mark))).containsExactly("start");
		assertThat(num(lastStart(), "index")).isZero();
	}

	@Test
	void afterResumeInPrepareEveryPlayerReadyStartsTheSong() {
		build();
		room.start(5);
		room.ready(MINA.id(), 0);
		room.pause();
		room.resume();
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).as("Mina's earlier ready was cleared").isEqualTo(Room.Phase.PREPARE);
		room.ready(MINA.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(outbox.ofType("start")).hasSize(1);
	}

	// ---- pause in REVEAL ----

	@Test
	void pauseInRevealStopsTheRevealPauseAndResumePreparesTheNextIndexAtOnce() {
		build();
		startAndPlay();
		answer(MINA);
		scheduler.advance(Duration.ofSeconds(2));

		int mark = outbox.mark();
		room.pause();
		assertThat(room.paused()).isTrue();
		scheduler.advance(Room.REVEAL_PAUSE);
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).as("the reveal pause does not fire while paused").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.songIndex()).isZero();

		room.resume();
		assertThat(room.paused()).isFalse();
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(num(lastPrepare(), "index")).isEqualTo(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(1);
	}

	@Test
	void pauseInTheLastRevealThenResumeSendsGameOver() {
		build();
		room.start(5);
		for (int k = 0; k < 5; k++) {
			if (k > 0) {
				scheduler.advance(Room.REVEAL_PAUSE);
			}
			assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
			readyAll(k);
			answer(k % 2 == 0 ? MINA : JOON);
		}
		assertThat(room.songIndex()).isEqualTo(4);

		int mark = outbox.mark();
		room.pause();
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);

		room.resume();
		List<Sent> over = outbox.since(mark);
		assertThat(types(over)).containsExactly("gameOver");
		assertThat(over.get(0).toAll()).isTrue();
		assertThat(rankingRows(over.get(0).message())).containsExactly("1:Mina:3", "2:Joon:2");
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(room.songIndex()).isEqualTo(-1);
		assertThat(room.paused()).isFalse();

		mark = outbox.mark();
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).isEmpty();
	}

	// ---- while paused ----

	@Test
	void whilePausedReadyIsIgnored() {
		build();
		room.start(5);
		room.pause();
		int mark = outbox.mark();
		readyAll(0);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.paused()).isTrue();
	}

	@Test
	void whilePausedACorrectChatIsIgnoredWithNoScoreAndNoMessage() {
		build();
		startAndPlay();
		scheduler.advance(Room.LEAD.plusSeconds(1));
		room.pause();
		int mark = outbox.mark();
		room.chat(MINA.id(), exactTitle());
		room.chat(JOON.id(), "  " + exactTitle().toUpperCase() + "!! ");
		assertThat(outbox.since(mark)).as("no correct, chat, reveal or scores").isEmpty();
		assertThat(room.scores()).containsExactly(Map.entry(MINA.id(), 0), Map.entry(JOON.id(), 0));
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.paused()).isTrue();
	}

	@Test
	void whilePausedAWrongChatIsIgnoredToo() {
		build();
		startAndPlay();
		scheduler.advance(Room.LEAD.plusSeconds(1));
		room.pause();
		int mark = outbox.mark();
		room.chat(MINA.id(), "not the title");
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void currentSongIsNullWhilePausedInPrepareAndInPlaying() {
		build();
		room.start(5);
		assertThat(room.currentSong()).isNotNull();
		room.pause();
		assertThat(room.currentSong()).as("paused in PREPARE").isNull();
		room.resume();
		assertThat(room.currentSong()).isNotNull();
		assertThat(room.currentSong().get("audioUrl")).isEqualTo(lastPrepare().get("audioUrl"));

		readyAll(0);
		assertThat(room.currentSong()).isNotNull();
		room.pause();
		assertThat(room.currentSong()).as("paused in PLAYING").isNull();
		room.resume();
		assertThat(room.currentSong()).isNotNull();
		assertThat(room.currentSong().get("audioUrl")).isEqualTo(lastPrepare().get("audioUrl"));
	}

	@Test
	void pauseTwiceAndResumeTwiceAreHarmlessInPlaying() {
		build();
		startAndPlay();
		int mark = outbox.mark();
		room.pause();
		room.pause();
		assertThat(room.paused()).isTrue();
		assertThat(outbox.since(mark)).isEmpty();

		room.resume();
		room.resume();
		assertThat(room.paused()).isFalse();
		assertThat(types(outbox.since(mark))).as("one prepare only").containsExactly("prepare");
		assertThat(room.songIndex()).isZero();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void pauseTwiceAndResumeTwiceInRevealAdvanceOneSongOnly() {
		build();
		startAndPlay();
		answer(MINA);
		int mark = outbox.mark();
		room.pause();
		room.pause();
		room.resume();
		room.resume();
		assertThat(types(outbox.since(mark))).containsExactly("prepare");
		assertThat(room.songIndex()).isEqualTo(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
	}

	@Test
	void resumeWhenNotPausedDoesNothing() {
		build();
		room.start(5);
		int mark = outbox.mark();
		room.resume();
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.paused()).isFalse();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);

		// The prepare timeout of the unpaused song still fires.
		scheduler.advance(Room.PREPARE_TIMEOUT);
		assertThat(types(outbox.since(mark))).containsExactly("start");
	}

	@Test
	void pauseInLobbyDoesNothing() {
		build();
		int pending = scheduler.pending();
		room.pause();
		assertThat(room.paused()).isFalse();
		assertThat(outbox.all()).isEmpty();
		assertThat(scheduler.pending()).isEqualTo(pending);
		room.resume();
		assertThat(outbox.all()).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);

		// The game then starts and runs normally.
		room.start(5);
		assertThat(types(outbox.all())).containsExactly("prepare");
		scheduler.advance(Room.PREPARE_TIMEOUT);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void scoresFromBeforeThePauseAreKept() {
		build();
		startAndPlay();
		answer(MINA);
		scheduler.advance(Room.REVEAL_PAUSE);
		readyAll(1);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.scores()).containsExactly(Map.entry(MINA.id(), 1), Map.entry(JOON.id(), 0));

		room.pause();
		assertThat(room.scores()).containsExactly(Map.entry(MINA.id(), 1), Map.entry(JOON.id(), 0));
		scheduler.advance(LONG);
		room.resume();
		assertThat(room.scores()).containsExactly(Map.entry(MINA.id(), 1), Map.entry(JOON.id(), 0));

		readyAll(1);
		answer(JOON);
		assertThat(scoreRows(outbox.last("scores").message())).containsExactly("Mina:1", "Joon:1");
	}

	// ---- generation ----

	// A scheduler whose cancel does nothing, so only the generation keeps a task from before the pause
	// stale (spec review N1).
	private static final class UncancellableScheduler implements GameScheduler {

		private final ManualScheduler delegate;

		UncancellableScheduler(ManualScheduler delegate) {
			this.delegate = delegate;
		}

		@Override
		public Cancellable schedule(Duration delay, Runnable task) {
			delegate.schedule(delay, task);
			return () -> {
			};
		}

	}

	@Test
	void aDeadlineQueuedBeforeThePauseStaysStaleAfterTheResumeEvenIfNotCancelled() {
		build(new UncancellableScheduler(scheduler));
		startAndPlay();
		long oldDeadline = num(lastStart(), "deadlineAt");
		scheduler.advance(Duration.ofSeconds(2));

		room.pause();
		room.resume();
		readyAll(0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		long newDeadline = num(lastStart(), "deadlineAt");
		assertThat(newDeadline).isGreaterThan(oldDeadline);

		int mark = outbox.mark();
		scheduler.advance(Duration.ofMillis(oldDeadline - clock.millis()));
		assertThat(outbox.since(mark)).as("the old deadline does nothing").isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Duration.ofMillis(newDeadline - clock.millis()));
		assertThat(types(outbox.since(mark))).containsExactly("reveal", "scores");
	}

	@Test
	void aRevealPauseQueuedBeforeThePauseDoesNotFireWhileStillPausedEvenIfNotCancelled() {
		build(new UncancellableScheduler(scheduler));
		startAndPlay();
		answer(MINA);
		room.pause();
		int mark = outbox.mark();
		scheduler.advance(LONG);
		assertThat(outbox.since(mark)).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
	}

}
