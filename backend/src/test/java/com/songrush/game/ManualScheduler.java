package com.songrush.game;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

// A GameScheduler the test drives by hand. advance(d) runs every task due at or before now + d,
// in due-time order (ties in scheduling order), including tasks scheduled while it runs. The clock
// is moved to each task's due time before the task runs, then to now + d. Cancelled tasks never run.
final class ManualScheduler implements GameScheduler {

	private final MutableClock clock;

	private final List<Task> tasks = new ArrayList<>();

	private long nextSeq;

	ManualScheduler(MutableClock clock) {
		this.clock = clock;
	}

	@Override
	public Cancellable schedule(Duration delay, Runnable task) {
		Objects.requireNonNull(delay, "delay");
		Objects.requireNonNull(task, "task");
		Instant due = delay.isNegative() ? clock.instant() : clock.instant().plus(delay);
		Task t = new Task(due, nextSeq++, task);
		tasks.add(t);
		return t;
	}

	void advance(Duration duration) {
		if (duration.isNegative()) {
			throw new IllegalArgumentException("cannot go back in time: " + duration);
		}
		Instant target = clock.instant().plus(duration);
		while (true) {
			tasks.removeIf(t -> t.cancelled);
			Task next = tasks.stream()
					.filter(t -> !t.due.isAfter(target))
					.min(Comparator.comparing((Task t) -> t.due).thenComparingLong(t -> t.seq))
					.orElse(null);
			if (next == null) {
				break;
			}
			tasks.remove(next);
			if (next.due.isAfter(clock.instant())) {
				clock.set(next.due);
			}
			next.task.run();
		}
		if (target.isAfter(clock.instant())) {
			clock.set(target);
		}
	}

	// Tasks that are scheduled and not cancelled.
	int pending() {
		return (int) tasks.stream().filter(t -> !t.cancelled).count();
	}

	private static final class Task implements Cancellable {

		final Instant due;

		final long seq;

		final Runnable task;

		boolean cancelled;

		Task(Instant due, long seq, Runnable task) {
			this.due = due;
			this.seq = seq;
			this.task = task;
		}

		@Override
		public void cancel() {
			cancelled = true;
		}

	}

}
