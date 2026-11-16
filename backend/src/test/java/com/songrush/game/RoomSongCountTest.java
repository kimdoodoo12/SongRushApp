package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.game.ChoiceFixture.Built;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 139 (D-137) criterion 1: a game has 5 to 100 songs. The fixture is ChoiceFixture with 101 or
// 99 covered anime, each its own group with one OP, so every song is drawable in a chat room.
class RoomSongCountTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Room room(int anime) {
		Built built = ChoiceFixture.build(dir.resolve("n" + anime), ChoiceFixture.coveredSingles(0, anime));
		return new Room(built.catalog(), tokens, scheduler, clock, new Random(139), outbox, List.of(MINA),
				built.covers());
	}

	@Test
	void oneHundredSongsStartWhenEnoughAreDrawable() {
		Room room = room(101);
		room.start(100);
		assertThat(outbox.ofType("error")).isEmpty();
		assertThat(room.phase()).isEqualTo(Room.Phase.PREPARE);
		assertThat(room.songIndex()).isEqualTo(0);
		assertThat(outbox.last("prepare").message()).containsEntry("index", 0).containsEntry("total", 100);
	}

	@Test
	void oneHundredAndOneAndFourAreBadSongCount() {
		Room room = room(101);
		for (int n : new int[] {101, 4}) {
			int mark = outbox.mark();
			room.start(n);
			List<Sent> sent = outbox.since(mark);
			assertThat(sent).as("start(" + n + ")").hasSize(1);
			assertThat(sent.get(0).toAll()).isTrue();
			assertThat(sent.get(0).message()).isEqualTo(Map.of("type", "error", "code", "bad-song-count"));
			assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
			assertThat(room.songIndex()).isEqualTo(-1);
		}
	}

	@Test
	void oneHundredWithTooFewDrawableIsStillNotEnoughSongs() {
		Room room = room(99);
		room.start(100);
		assertThat(outbox.all()).extracting(Sent::message)
				.containsExactly(Map.of("type", "error", "code", "not-enough-songs", "available", 99));
		assertThat(room.phase()).isEqualTo(Room.Phase.LOBBY);
	}

}
