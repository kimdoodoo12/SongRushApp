package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 050 (B-008) criterion 1: a skip refused for lack of a chat token is tagged for the skip vote,
// so the client can tell it from a refused chat; a refused chat keeps the untagged error. Both go
// to the sender only (docs/cycles/050-own-skip-vote.md). Cycle 036 fakes.
class RoomSkipErrorTest {

	private static final PlayerRef MINA = new PlayerRef("p1", "Mina");

	private static final PlayerRef JOON = new PlayerRef("p2", "Joon");

	private static final PlayerRef HANA = new PlayerRef("p3", "Hana");

	private static final long[] FIVE = {60000, 120000, 80000, 85000, 89000};

	private static final Map<String, Object> SKIP_LIMITED = Map.of("type", "error", "code", "rate-limited",
			"for", "skipVote");

	private static final Map<String, Object> CHAT_LIMITED = Map.of("type", "error", "code", "rate-limited");

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private Room room;

	// Three players in song 0 after startAt.
	private void playing() {
		Catalog catalog = GameFixture.build(dir, FIVE, 0);
		room = new Room(catalog, tokens, scheduler, clock, new Random(50), outbox, List.of(MINA, JOON, HANA));
		room.start(5);
		for (PlayerRef p : List.of(MINA, JOON, HANA)) {
			room.ready(p.id(), 0);
		}
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
		scheduler.advance(Room.LEAD);
	}

	// Spends all five of Mina's tokens on chat misses.
	private void drainByChat() {
		for (int i = 1; i <= 5; i++) {
			room.chat(MINA.id(), "miss " + i);
		}
	}

	// The single message sent since mark, which must go to the sender only, with exactly these fields.
	private static void assertOnlyToSender(List<Sent> sent, PlayerRef sender, Map<String, Object> expected) {
		assertThat(sent).as("exactly one message").hasSize(1);
		assertThat(sent.get(0).toAll()).as("not a broadcast").isFalse();
		assertThat(sent.get(0).to()).as("only the sender").isEqualTo(sender.id());
		assertThat(sent.get(0).message()).isEqualTo(expected);
	}

	@Test
	void aRateLimitedSkipGivesTheSenderOnlyTheErrorTaggedForTheSkipVote() {
		playing();
		drainByChat();
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		assertOnlyToSender(outbox.since(mark), MINA, SKIP_LIMITED);
		assertThat(room.phase()).isEqualTo(Room.Phase.PLAYING);
	}

	@Test
	void aSkipRefusedAfterFiveSkipTogglesIsAlsoTagged() {
		playing();
		for (int i = 0; i < 5; i++) {
			room.skip(MINA.id(), 0);
		}
		assertThat(outbox.ofType("skipState")).hasSize(5);
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		assertOnlyToSender(outbox.since(mark), MINA, SKIP_LIMITED);
		assertThat(outbox.last("skipState").message()).containsEntry("votes", 1);
	}

	@Test
	void aRateLimitedChatGivesTheSenderOnlyTheUntaggedError() {
		playing();
		drainByChat();
		int mark = outbox.mark();
		room.chat(MINA.id(), "miss 6");
		assertOnlyToSender(outbox.since(mark), MINA, CHAT_LIMITED);
	}

	@Test
	void aChatRefusedAfterSkipsSpentTheTokensIsStillUntagged() {
		playing();
		for (int i = 0; i < 5; i++) {
			room.skip(MINA.id(), 0);
		}
		int mark = outbox.mark();
		room.chat(MINA.id(), "one more");
		assertOnlyToSender(outbox.since(mark), MINA, CHAT_LIMITED);
	}

	@Test
	void interleavedRefusalsEachCarryTheirOwnTagAndReachNobodyElse() {
		playing();
		drainByChat();
		int mark = outbox.mark();
		room.skip(MINA.id(), 0);
		room.chat(MINA.id(), "again");
		room.skip(MINA.id(), 0);
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).hasSize(3);
		for (Sent s : sent) {
			assertThat(s.to()).as("only Mina").isEqualTo(MINA.id());
		}
		assertThat(sent.stream().map(Sent::message).toList()).containsExactly(SKIP_LIMITED, CHAT_LIMITED,
				SKIP_LIMITED);
	}

	@Test
	void anIgnoredSkipWithAnEmptyBucketSendsNothing() {
		playing();
		drainByChat();
		int mark = outbox.mark();
		room.skip(MINA.id(), 1);
		room.skip("ghost", 0);
		assertThat(outbox.since(mark)).as("an ignored skip is not judged, so no error").isEmpty();
	}

}
