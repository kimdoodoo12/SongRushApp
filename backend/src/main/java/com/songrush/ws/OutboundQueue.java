package com.songrush.ws;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// One session's outbound frames (docs/cycles/037-solo-websocket.md). offer never blocks and never
// throws; a shared sender pool drains the queue in order, one frame at a time, and only the drain
// calls Sink.send. Capacity counts the frame being sent until its send returns. A full queue or a
// failed send closes the sink once with 1008; later offers are dropped.
public final class OutboundQueue {

	public interface Sink {

		void send(String text) throws Exception;

		void close(int status);

	}

	static final int POLICY_VIOLATION = 1008;

	private static final Logger log = LoggerFactory.getLogger(OutboundQueue.class);

	private final Sink sink;

	private final Executor senders;

	private final int capacity;

	private final Object lock = new Object();

	private final Queue<String> pending = new ArrayDeque<>();

	// Queued frames plus the one being sent.
	private int count;

	private boolean draining;

	private volatile boolean closed;

	// Guarded by lock: the sink was closed (or the queue stopped), so it is never closed again.
	private boolean sinkClosed;

	public OutboundQueue(Sink sink, Executor senders, int capacity) {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacity must be positive: " + capacity);
		}
		this.sink = sink;
		this.senders = senders;
		this.capacity = capacity;
	}

	public boolean offer(String text) {
		boolean startDrain = false;
		boolean full = false;
		synchronized (lock) {
			if (closed) {
				return false;
			}
			if (count >= capacity) {
				markClosed();
				full = true;
			}
			else {
				pending.add(text);
				count++;
				if (!draining) {
					draining = true;
					startDrain = true;
				}
			}
		}
		if (full) {
			closeSink("queue full");
			return false;
		}
		if (startDrain) {
			try {
				senders.execute(this::drain);
			}
			catch (RuntimeException e) {
				// RejectedExecutionException when the pool is shut down; nothing can be sent any more.
				fail("sender pool rejected the drain (" + e.getClass().getSimpleName() + ")");
				return false;
			}
		}
		return true;
	}

	public boolean isClosed() {
		return closed;
	}

	// Stops the queue without closing the sink (the session is already closed).
	void stop() {
		synchronized (lock) {
			markClosed();
			sinkClosed = true;
		}
	}

	private void drain() {
		while (true) {
			String next;
			synchronized (lock) {
				if (closed) {
					draining = false;
					return;
				}
				next = pending.poll();
				if (next == null) {
					draining = false;
					return;
				}
			}
			try {
				sink.send(next);
			}
			catch (Exception e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				fail("send failed (" + e.getClass().getSimpleName() + ")");
				synchronized (lock) {
					draining = false;
				}
				return;
			}
			synchronized (lock) {
				if (!closed) {
					count--;
				}
			}
		}
	}

	private void fail(String reason) {
		synchronized (lock) {
			markClosed();
		}
		closeSink(reason);
	}

	// Caller holds the lock.
	private void markClosed() {
		closed = true;
		pending.clear();
		count = 0;
	}

	// Closes the sink and logs the reason, once; never after stop().
	private void closeSink(String reason) {
		synchronized (lock) {
			if (sinkClosed) {
				return;
			}
			sinkClosed = true;
		}
		log.warn("closing {} with {}: {}", sink, POLICY_VIOLATION, reason);
		try {
			sink.close(POLICY_VIOLATION);
		}
		catch (RuntimeException e) {
			log.warn("closing {} failed: {}", sink, e.getClass().getSimpleName());
		}
	}

}
