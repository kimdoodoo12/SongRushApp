package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.songrush.audio.AudioTokens;
import com.songrush.catalog.Catalog;
import com.songrush.catalog.Theme;
import com.songrush.game.RecordingOutbox.Sent;

// Cycle 113 (B-032, D-123) acceptance 1: while a lobby has no running room, a chat from a connected
// member goes to every connected member (sender included) as {type:"chat", name, text}, one toPlayer
// per connected member in join order, under the same flood, length and invisible rules as Room.chat,
// and never answer-checked. On the cycle 036 fakes (docs/cycles/113-lobby-chat.md).
class LobbyChatTest {

	private static final String CODE = "ABCDE";

	private static final String P1 = "pid-1";

	private static final String P2 = "pid-2";

	private static final String P3 = "pid-3";

	private static final long[] SEVEN = {60000, 120000, 80000, 85000, 89000, 75000, 95000};

	@TempDir
	Path dir;

	private final MutableClock clock = new MutableClock();

	private final ManualScheduler scheduler = new ManualScheduler(clock);

	private final RecordingOutbox outbox = new RecordingOutbox();

	private final AudioTokens tokens = new AudioTokens(clock);

	private final AtomicInteger closedCalls = new AtomicInteger();

	private Catalog catalog;

	private Lobby lobby;

	private void build() {
		catalog = GameFixture.build(dir, SEVEN, 0);
		lobby = new Lobby(CODE, catalog, tokens, scheduler, clock, new Random(113), outbox,
				closedCalls::incrementAndGet);
	}

	@AfterEach
	void lobbyNeverBroadcasts() {
		assertThat(outbox.all()).as("no toAll at all; every message is a toPlayer").noneMatch(Sent::toAll);
	}

	// ---- helpers ----

	private static List<String> types(List<Sent> sent) {
		return sent.stream().map(Sent::type).toList();
	}

	private List<Sent> toSince(int mark, String playerId) {
		return outbox.since(mark).stream().filter(s -> playerId.equals(s.to())).toList();
	}

	private void joinTwo() {
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
	}

	// The sent list is exactly one chat{name, text} to each of the ids, in that order.
	private static void assertChatTo(List<Sent> sent, String name, String text, String... ids) {
		assertThat(sent).extracting(Sent::to).as("recipients in join order").containsExactly(ids);
		for (Sent s : sent) {
			assertThat(s.message().keySet()).as("chat keys").containsExactly("type", "name", "text");
			assertThat(s.message()).containsEntry("type", "chat").containsEntry("name", name)
					.containsEntry("text", text);
		}
	}

	// ---- broadcast before any game ----

	@Test
	void aLineBeforeAnyGameReachesEveryConnectedMemberIncludingTheSender() {
		build();
		joinTwo();
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);

		int mark = outbox.mark();
		lobby.chat(P2, "hello");
		assertChatTo(outbox.since(mark), "Joon", "hello", P1, P2);

		mark = outbox.mark();
		lobby.chat(P1, "hi Joon");
		assertChatTo(outbox.since(mark), "Mina", "hi Joon", P1, P2);
	}

	@Test
	void theLineCarriesTheAssignedNameAndGoesOnlyToConnectedMembersInJoinOrder() {
		build();
		assertThat(lobby.join(P1, "Mina")).isEqualTo("Mina");
		assertThat(lobby.join(P2, "Joon")).isEqualTo("Joon");
		assertThat(lobby.join(P3, "Mina")).isEqualTo("Mina (2)");
		lobby.disconnect(P2);

		int mark = outbox.mark();
		lobby.chat(P3, "hi");
		assertChatTo(outbox.since(mark), "Mina (2)", "hi", P1, P3);
	}

	@Test
	void aLineThatIsATitleIsNeverAnswerCheckedInTheLobby() {
		build();
		joinTwo();
		Theme theme = catalog.theme(catalog.drawSet().get(0)).orElseThrow();
		String title = catalog.anime(theme.animeId()).orElseThrow().titles().get("en").get(0);

		int mark = outbox.mark();
		lobby.chat(P1, title);
		assertChatTo(outbox.since(mark), "Mina", title, P1, P2);
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
	}

	// ---- nothing is sent ----

	@Test
	void aDisconnectedOrUnknownMemberSendsNothing() {
		build();
		joinTwo();
		lobby.disconnect(P2);

		int mark = outbox.mark();
		lobby.chat(P2, "in grace");
		lobby.chat(P3, "never joined");
		assertThat(outbox.since(mark)).isEmpty();

		lobby.leave(P2);
		mark = outbox.mark();
		lobby.chat(P2, "gone");
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void aClosedLobbySendsNothing() {
		build();
		joinTwo();
		scheduler.advance(Lobby.IDLE_TTL);
		assertThat(lobby.closed()).isTrue();

		int mark = outbox.mark();
		lobby.chat(P1, "hello");
		assertThat(outbox.since(mark)).isEmpty();
	}

	@Test
	void nullHangulFillerAndBlankLinesSendNothing() {
		build();
		joinTwo();

		int mark = outbox.mark();
		lobby.chat(P1, null);
		lobby.chat(P1, String.valueOf((char) 0x3164));
		lobby.chat(P1, "   ");
		assertThat(outbox.since(mark)).isEmpty();
	}

	// ---- shared rules ----

	@Test
	void sixLinesAtOneInstantGiveFiveBroadcastsAndOneRateLimitedThenHalfASecondRefillsOne() {
		build();
		joinTwo();

		int mark = outbox.mark();
		for (int i = 1; i <= 6; i++) {
			lobby.chat(P1, "line " + i);
		}
		List<Sent> joon = toSince(mark, P2);
		assertThat(types(joon)).as("Joon gets five lines and no error").containsExactly("chat", "chat", "chat",
				"chat", "chat");
		assertThat(joon).extracting(s -> s.message().get("text")).containsExactly("line 1", "line 2", "line 3",
				"line 4", "line 5");
		List<Sent> mina = toSince(mark, P1);
		assertThat(types(mina)).as("Mina gets her five lines, then rate-limited").containsExactly("chat", "chat",
				"chat", "chat", "chat", "error");
		assertThat(mina.get(5).message()).containsEntry("code", "rate-limited");

		// Another member has a bucket of their own.
		mark = outbox.mark();
		lobby.chat(P2, "mine");
		assertChatTo(outbox.since(mark), "Joon", "mine", P1, P2);

		// Still empty at the same instant.
		mark = outbox.mark();
		lobby.chat(P1, "too soon");
		assertThat(outbox.since(mark)).extracting(Sent::to).containsExactly(P1);
		assertThat(outbox.since(mark).get(0).message()).containsEntry("type", "error")
				.containsEntry("code", "rate-limited");

		clock.advance(Duration.ofMillis(500));
		mark = outbox.mark();
		lobby.chat(P1, "after 500 ms");
		assertChatTo(outbox.since(mark), "Mina", "after 500 ms", P1, P2);
	}

	@Test
	void aLineOf201CharactersIsTooLongToTheSenderOnly() {
		build();
		joinTwo();

		int mark = outbox.mark();
		lobby.chat(P1, "a".repeat(Room.MAX_CHAT + 1));
		List<Sent> sent = outbox.since(mark);
		assertThat(sent).extracting(Sent::to).containsExactly(P1);
		assertThat(sent.get(0).message()).containsEntry("type", "error").containsEntry("code", "too-long");

		mark = outbox.mark();
		String max = "b".repeat(Room.MAX_CHAT);
		lobby.chat(P1, max);
		assertChatTo(outbox.since(mark), "Mina", max, P1, P2);
	}

	// ---- after a game ----

	@Test
	void afterAGameHasEndedTheLobbyBroadcastsChatAgain() {
		build();
		joinTwo();
		lobby.start(P1, 5);
		assertThat(lobby.phase()).isNotEqualTo(Room.Phase.LOBBY);

		// Nobody answers: each song runs out on its own; step until the game is over.
		for (int s = 0; s < 30 * 60 && lobby.phase() != Room.Phase.LOBBY; s++) {
			scheduler.advance(Duration.ofSeconds(1));
		}
		assertThat(lobby.phase()).isEqualTo(Room.Phase.LOBBY);
		assertThat(lobby.closed()).isFalse();
		assertThat(outbox.ofType("gameOver")).isNotEmpty();

		int mark = outbox.mark();
		lobby.chat(P2, "gg");
		assertChatTo(outbox.since(mark), "Joon", "gg", P1, P2);
	}

}
