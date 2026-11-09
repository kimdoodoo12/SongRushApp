package com.songrush.game;

// How a game's songs are scored (D-112, docs/cycles/101-game-modes.md): first come, where the first
// correct answer wins the song and ends it, or ranked, where every player may answer once and is
// scored by arrival rank. Fixed per game by the host's start request.
public enum GameMode {

	FIRST("first"), RANKED("ranked");

	private final String wire;

	GameMode(String wire) {
		this.wire = wire;
	}

	// The exact value of a start request's gameMode and of settings.gameMode.
	public String wire() {
		return wire;
	}

	// The mode with that exact (case-sensitive) wire value, or null.
	public static GameMode fromWire(String value) {
		for (GameMode mode : values()) {
			if (mode.wire.equals(value)) {
				return mode;
			}
		}
		return null;
	}

}
