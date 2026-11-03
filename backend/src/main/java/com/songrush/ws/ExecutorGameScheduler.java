package com.songrush.ws;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.songrush.game.Cancellable;
import com.songrush.game.GameScheduler;

// A room's thread: one single-thread ScheduledExecutorService runs every inbound message and every
// timer of the room, so the Room is only ever called on that thread. Every task is wrapped so an
// exception is logged and the thread survives.
final class ExecutorGameScheduler implements GameScheduler {

	private static final Logger log = LoggerFactory.getLogger(ExecutorGameScheduler.class);

	private static final AtomicInteger THREADS = new AtomicInteger();

	private static final Cancellable NOTHING = () -> {
	};

	private final String name;

	private final ScheduledExecutorService executor;

	ExecutorGameScheduler(String name) {
		this.name = name;
		this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "room-" + THREADS.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	// A timer of the room. Once the room is discarded the task is dropped (nothing more is sent).
	@Override
	public Cancellable schedule(Duration delay, Runnable task) {
		try {
			ScheduledFuture<?> future = executor.schedule(wrap(task), delay.toNanos(), TimeUnit.NANOSECONDS);
			return () -> future.cancel(false);
		}
		catch (RejectedExecutionException e) {
			return NOTHING;
		}
	}

	// Runs a task on the room thread; throws RejectedExecutionException once shut down.
	void execute(Runnable task) {
		executor.execute(wrap(task));
	}

	void shutdownNow() {
		executor.shutdownNow();
	}

	private Runnable wrap(Runnable task) {
		return () -> {
			try {
				task.run();
			}
			catch (RuntimeException e) {
				// Class and top frame only: an exception message could carry game text.
				StackTraceElement[] trace = e.getStackTrace();
				log.error("task of {} failed: {} at {}", name, e.getClass().getName(),
						trace.length == 0 ? "?" : trace[0]);
			}
		};
	}

}
