package com.songrush.game;

import java.util.function.Predicate;
import java.util.random.RandomGenerator;

// Room codes: 5 characters of an alphabet without the look-alikes I, L, O, 0 and 1
// (docs/cycles/040-rooms-lobby.md).
public final class RoomCodes {

	public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	public static final int LENGTH = 5;

	static final int MAX_TRIES = 100;

	private RoomCodes() {
	}

	// Draws one code per try until one is not taken; throws after MAX_TRIES taken codes.
	public static String next(RandomGenerator random, Predicate<String> taken) {
		for (int i = 0; i < MAX_TRIES; i++) {
			StringBuilder code = new StringBuilder(LENGTH);
			for (int j = 0; j < LENGTH; j++) {
				code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
			}
			String c = code.toString();
			if (!taken.test(c)) {
				return c;
			}
		}
		throw new IllegalStateException("no free room code after " + MAX_TRIES + " tries");
	}

}
