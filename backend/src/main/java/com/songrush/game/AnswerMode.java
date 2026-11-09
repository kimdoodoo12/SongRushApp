package com.songrush.game;

// How a game's songs are answered (D-111, docs/cycles/100-choice-answers.md): by chat only, or also
// by picking one of the song's cover choices. Fixed per game by the host's start request.
public enum AnswerMode {

	CHAT("chat"), CHOICE("choice");

	private final String wire;

	AnswerMode(String wire) {
		this.wire = wire;
	}

	// The exact value of a start request's answerMode and of settings.answerMode.
	public String wire() {
		return wire;
	}

	// The mode with that exact (case-sensitive) wire value, or null.
	public static AnswerMode fromWire(String value) {
		for (AnswerMode mode : values()) {
			if (mode.wire.equals(value)) {
				return mode;
			}
		}
		return null;
	}

}
