package com.songrush.game;

// A scheduled task that can be cancelled before it runs.
public interface Cancellable {

	void cancel();

}
