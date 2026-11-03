package com.songrush.game;

import java.time.Duration;

// Runs a task after a delay on the room's thread (the production scheduler is B-007).
public interface GameScheduler {

	Cancellable schedule(Duration delay, Runnable task);

}
