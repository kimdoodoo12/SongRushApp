package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.songrush.game.Outbox;

import tools.jackson.databind.JsonNode;

// Cycle 037 (B-007) criteria 5-6: OutboundQueue and SocketOutbox without a server, on a fake sink
// (docs/cycles/037-solo-websocket.md, Interface note). Capacity counts the message being sent.
class OutboundQueueTest {

	private static final int CAPACITY = 256;

	private static final int POLICY_VIOLATION = 1008;

	private static final long WAIT_MS = 5_000;

	private final ExecutorService senders = Executors.newFixedThreadPool(4);

	private final List<FakeSink> sinks = new ArrayList<>();

	@AfterEach
	void shutdown() throws InterruptedException {
		sinks.forEach(FakeSink::release);
		senders.shutdownNow();
		assertThat(senders.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}

	// A sink that records sends and closes. When blocking, every send waits until release();
	// when throwing, every send throws.
	static final class FakeSink implements OutboundQueue.Sink {

		final List<String> sent = new CopyOnWriteArrayList<>();

		final List<Integer> closes = new CopyOnWriteArrayList<>();

		final CountDownLatch entered = new CountDownLatch(1);

		final CountDownLatch closed = new CountDownLatch(1);

		private final CountDownLatch released;

		private final boolean throwing;

		FakeSink(boolean blocking, boolean throwing) {
			this.released = new CountDownLatch(blocking ? 1 : 0);
			this.throwing = throwing;
		}

		@Override
		public void send(String text) throws Exception {
			entered.countDown();
			if (throwing) {
				throw new IOException("fake send failure");
			}
			released.await();
			sent.add(text);
		}

		@Override
		public void close(int status) {
			closes.add(status);
			closed.countDown();
		}

		void release() {
			released.countDown();
		}

	}

	private FakeSink sink(boolean blocking, boolean throwing) {
		FakeSink sink = new FakeSink(blocking, throwing);
		sinks.add(sink);
		return sink;
	}

	private static void awaitSent(FakeSink sink, int count) throws InterruptedException {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (sink.sent.size() < count && System.currentTimeMillis() < deadline) {
			Thread.sleep(10);
		}
		assertThat(sink.sent).hasSize(count);
	}

	// Criterion 5: the blocked message counts; offer 257 is dropped and closes with 1008
	@Test
	void fullQueueClosesWithPolicyViolation() throws Exception {
		FakeSink sink = sink(true, false);
		OutboundQueue queue = new OutboundQueue(sink, senders, CAPACITY);

		assertThat(queue.offer("m0")).isTrue();
		assertThat(sink.entered.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("first send started").isTrue();
		long begin = System.nanoTime();
		for (int i = 1; i < CAPACITY; i++) {
			assertThat(queue.offer("m" + i)).as("offer %d", i).isTrue();
		}
		assertThat(Duration.ofNanos(System.nanoTime() - begin)).as("offers do not block")
				.isLessThan(Duration.ofSeconds(2));
		assertThat(queue.isClosed()).isFalse();

		assertThat(queue.offer("m" + CAPACITY)).as("offer 257").isFalse();
		assertThat(sink.closed.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("session closed").isTrue();
		assertThat(sink.closes).containsOnly(POLICY_VIOLATION);
		assertThat(queue.isClosed()).isTrue();

		// Criterion 5: later offers are dropped without an exception
		for (int i = 0; i < 10; i++) {
			int n = i;
			assertThatCode(() -> assertThat(queue.offer("late" + n)).isFalse()).doesNotThrowAnyException();
		}
		sink.release();
	}

	// Criterion 5: the offers themselves return at once while the sink is blocked
	@Test
	void offersDoNotWaitForABlockedSink() throws Exception {
		FakeSink sink = sink(true, false);
		OutboundQueue queue = new OutboundQueue(sink, senders, CAPACITY);
		assertThat(queue.offer("first")).isTrue();
		assertThat(sink.entered.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
		long begin = System.nanoTime();
		for (int i = 1; i < CAPACITY; i++) {
			queue.offer("m" + i);
		}
		assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(2));
		sink.release();
		awaitSent(sink, CAPACITY);
		assertThat(queue.isClosed()).isFalse();
	}

	// Criterion 5: delivered in order, one at a time, on a multi-thread sender pool
	@Test
	void deliversInOrder() throws Exception {
		FakeSink sink = sink(false, false);
		OutboundQueue queue = new OutboundQueue(sink, senders, CAPACITY);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			String m = "m" + i;
			expected.add(m);
			assertThat(queue.offer(m)).isTrue();
			if (i % 100 == 99) {
				awaitSent(sink, i + 1);
			}
		}
		awaitSent(sink, expected.size());
		assertThat(sink.sent).containsExactlyElementsOf(expected);
		assertThat(sink.closes).isEmpty();
		assertThat(queue.isClosed()).isFalse();
	}

	// Criterion 5: two queues on one pool keep their own order
	@Test
	void queuesSharingAPoolKeepTheirOwnOrder() throws Exception {
		FakeSink a = sink(false, false);
		FakeSink b = sink(false, false);
		OutboundQueue qa = new OutboundQueue(a, senders, CAPACITY);
		OutboundQueue qb = new OutboundQueue(b, senders, CAPACITY);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			expected.add("m" + i);
			assertThat(qa.offer("m" + i)).isTrue();
			assertThat(qb.offer("m" + i)).isTrue();
		}
		awaitSent(a, 200);
		awaitSent(b, 200);
		assertThat(a.sent).containsExactlyElementsOf(expected);
		assertThat(b.sent).containsExactlyElementsOf(expected);
	}

	// Criterion 5: a failing send closes the session; the next offer does not throw
	@Test
	void failingSendClosesTheSession() throws Exception {
		FakeSink sink = sink(false, true);
		OutboundQueue queue = new OutboundQueue(sink, senders, CAPACITY);
		assertThatCode(() -> queue.offer("boom")).doesNotThrowAnyException();
		assertThat(sink.closed.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("session closed").isTrue();
		assertThat(sink.closes).containsOnly(POLICY_VIOLATION);
		assertThat(queue.isClosed()).isTrue();
		assertThatCode(() -> assertThat(queue.offer("next")).isFalse()).doesNotThrowAnyException();
	}

	// Criterion 6: with a queue whose sink throws, toAll and toPlayer return normally
	@Test
	void outboxNeverThrows() throws Exception {
		FakeSink sink = sink(false, true);
		Map<String, OutboundQueue> queues = new ConcurrentHashMap<>();
		queues.put("p1", new OutboundQueue(sink, senders, CAPACITY));
		Outbox outbox = new SocketOutbox(queues);
		for (int i = 0; i < 5; i++) {
			assertThatCode(() -> outbox.toAll(message("chat", "name", "Mina", "text", "hi")))
					.doesNotThrowAnyException();
			assertThatCode(() -> outbox.toPlayer("p1", message("error", "code", "too-long")))
					.doesNotThrowAnyException();
		}
		assertThat(sink.closed.await(WAIT_MS, TimeUnit.MILLISECONDS)).isTrue();
		assertThatCode(() -> outbox.toAll(message("chat", "name", "Mina", "text", "after close")))
				.doesNotThrowAnyException();
		assertThatCode(() -> outbox.toPlayer("p1", message("chat", "name", "Mina", "text", "after close")))
				.doesNotThrowAnyException();
		// An unknown player and a message Jackson cannot serialize are swallowed too.
		assertThatCode(() -> outbox.toPlayer("nobody", message("chat", "text", "x"))).doesNotThrowAnyException();
		assertThatCode(() -> outbox.toAll(message("chat", "text", new Object()))).doesNotThrowAnyException();
	}

	// Criterion 6 (Outbox): JSON to the right queues; the map is read at each call
	@Test
	void outboxSerializesToTheRightQueues() throws Exception {
		FakeSink s1 = sink(false, false);
		FakeSink s2 = sink(false, false);
		Map<String, OutboundQueue> queues = new ConcurrentHashMap<>();
		queues.put("p1", new OutboundQueue(s1, senders, CAPACITY));
		queues.put("p2", new OutboundQueue(s2, senders, CAPACITY));
		Outbox outbox = new SocketOutbox(queues);

		outbox.toPlayer("p2", message("error", "code", "too-long"));
		awaitSent(s2, 1);
		JsonNode error = TestClient.MAPPER.readTree(s2.sent.get(0));
		assertThat(error.path("type").asString(null)).isEqualTo("error");
		assertThat(error.path("code").asString(null)).isEqualTo("too-long");
		assertThat(s1.sent).isEmpty();

		FakeSink s3 = sink(false, false);
		queues.put("p3", new OutboundQueue(s3, senders, CAPACITY));
		outbox.toAll(message("correct", "index", 2, "name", "Mina", "winner", null));
		awaitSent(s1, 1);
		awaitSent(s2, 2);
		awaitSent(s3, 1);
		for (String json : List.of(s1.sent.get(0), s2.sent.get(1), s3.sent.get(0))) {
			JsonNode node = TestClient.MAPPER.readTree(json);
			assertThat(node.path("type").asString(null)).isEqualTo("correct");
			assertThat(node.path("index").intValue()).isEqualTo(2);
			assertThat(node.path("name").asString(null)).isEqualTo("Mina");
			assertThat(node.has("winner")).isTrue();
			assertThat(node.path("winner").isNull()).isTrue();
		}
	}

	private static Map<String, Object> message(String type, Object... keysAndValues) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", type);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			m.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return m;
	}

}
