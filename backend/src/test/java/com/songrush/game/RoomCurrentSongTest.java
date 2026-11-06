package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Anime;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;

// Cycle 042 (B-005b) criterion 2: Room.currentSong() on the cycle 036 fakes. It is null in LOBBY and
// REVEAL; in PREPARE it is {index, total, audioUrl} and in PLAYING it adds startAt and deadlineAt,
// all equal to the prepare and start messages of that song (docs/cycles/042-reconnect.md).
class RoomCurrentSongTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Catalog catalog;

	private Room room;

	private void build() {
		catalog = GameFixture.build(dir, FIVE, 0);
		room = new Room(catalog, tokens, scheduler, clock, new Random(42), outbox, List.of(MINA, JOON));
	}

	private static long num(Map<String, Object> message, String key) {
		Object value = message.get(key);
		assertThat(value).as(key).isInstanceOf(Number.class);
		return ((Number) value).longValue();
	}

	private Anime currentAnime() {
		String url = (String) outbox.last("prepare").message().get("audioUrl");
		int themeId = tokens.resolve(url.substring("/audio/".length())).orElseThrow();
		Theme theme = catalog.theme(themeId).orElseThrow();
		return catalog.anime(theme.animeId()).orElseThrow();
	}

	private void assertMatchesPrepare(Map<String, Object> song) {
		Map<String, Object> prepare = outbox.last("prepare").message();
		assertThat(num(song, "index")).isEqualTo(num(prepare, "index"));
		assertThat(num(song, "total")).isEqualTo(num(prepare, "total"));
		assertThat(song.get("audioUrl")).isInstanceOf(String.class).isEqualTo(prepare.get("audioUrl"));
		String url = (String) song.get("audioUrl");
		assertThat(tokens.resolve(url.substring("/audio/".length()))).as("the token is still valid").isPresent();
	}

	@Test
	void nullInTheLobbyBeforeAnyGame() {
		build();
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(room.currentSong()).isNull();
	}

	@Test
	void prepareHasIndexTotalAndAudioUrlOnly() {
		build();
		room.start(5);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> song = room.currentSong();
		assertThat(song).isNotNull();
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertMatchesPrepare(song);
		assertThat(num(song, "index")).isZero();
		assertThat(num(song, "total")).isEqualTo(5);
	}

	@Test
	void playingAddsStartAtAndDeadlineAtFromTheStartMessage() {
		build();
		room.start(5);
		room.ready(MINA.id(), 0);
		room.ready(JOON.id(), 0);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		Map<String, Object> song = room.currentSong();
		assertThat(song).isNotNull();
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt");
		assertMatchesPrepare(song);
		Map<String, Object> start = outbox.last("start").message();
		assertThat(num(song, "startAt")).isEqualTo(num(start, "startAt"));
		assertThat(num(song, "deadlineAt")).isEqualTo(num(start, "deadlineAt"));

		// Still the same during the song, after the lead.
		scheduler.advance(Room.LEAD);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		assertThat(room.currentSong()).isEqualTo(song);
	}

	@Test
	void prepareTimeoutStartAlsoGivesStartAtAndDeadlineAt() {
		build();
		room.start(5);
		scheduler.advance(Room.PREPARE_TIMEOUT);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		Map<String, Object> song = room.currentSong();
		Map<String, Object> start = outbox.last("start").message();
		assertThat(song.keySet()).containsExactlyInAnyOrder("index", "total", "audioUrl", "startAt", "deadlineAt");
		assertThat(num(song, "startAt")).isEqualTo(num(start, "startAt"));
		assertThat(num(song, "deadlineAt")).isEqualTo(num(start, "deadlineAt"));
	}

	@Test
	void nullInRevealAndThenTheNextSongsValues() {
		build();
		room.start(5);
		room.ready(MINA.id(), 0);
		room.ready(JOON.id(), 0);
		scheduler.advance(Room.LEAD);
		room.chat(MINA.id(), currentAnime().titles().get("en").get(0));
		assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
		assertThat(room.currentSong()).isNull();

		scheduler.advance(Room.REVEAL_PAUSE);
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		Map<String, Object> song = room.currentSong();
		assertThat(song.keySet()).as("a new song drops the previous start values")
				.containsExactlyInAnyOrder("index", "total", "audioUrl");
		assertMatchesPrepare(song);
		assertThat(num(song, "index")).isEqualTo(1);
	}

	@Test
	void nullInRevealAfterTheDeadlineAndInTheLobbyAfterGameOver() {
		build();
		room.start(5);
		for (int k = 0; k < 5; k++) {
			room.ready(MINA.id(), k);
			room.ready(JOON.id(), k);
			Map<String, Object> start = outbox.last("start").message();
			scheduler.advance(Duration.ofMillis(num(start, "deadlineAt") - clock.millis()));
			assertThat(room.phase()).isEqualTo(Room.Phase.REVEAL);
			assertThat(room.currentSong()).isNull();
			scheduler.advance(Room.REVEAL_PAUSE);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(outbox.ofType("gameOver")).hasSize(1);
		assertThat(room.currentSong()).isNull();
	}

}
