package com.songrush.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

// Cycle 040 (B-005a) criterion 1: room codes are 5 characters of the unambiguous alphabet, a taken
// code is redrawn, and an always-taken predicate fails after 100 tries
// (docs/cycles/040-rooms-lobby.md).
class RoomCodesTest {

	@Test
	void constantsMatchTheSpec() {
		assertThat(RoomCodes.ALPHABET).isEqualTo("ABCDEFGHJKMNPQRSTUVWXYZ23456789");
		assertThat(RoomCodes.LENGTH).isEqualTo(5);
	}

	@Test
	void tenThousandCodesAreFiveCharactersOfTheAlphabet() {
		Random random = new Random(40);
		Set<Character> seen = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			String code = RoomCodes.next(random, c -> false);
			assertThat(code).as("code %d", i).hasSize(5);
			for (char ch : code.toCharArray()) {
				assertThat(RoomCodes.ALPHABET.indexOf(ch)).as("char %s of %s is in the alphabet", ch, code)
						.isGreaterThanOrEqualTo(0);
				seen.add(ch);
			}
		}
		assertThat(seen).as("every alphabet character is drawn").hasSize(RoomCodes.ALPHABET.length());
	}

	@Test
	void takenCodesAreRedrawnAndTheFourthDrawIsReturned() {
		Random reference = new Random(4040);
		List<String> draws = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			draws.add(RoomCodes.next(reference, c -> false));
		}

		List<String> asked = new ArrayList<>();
		String code = RoomCodes.next(new Random(4040), c -> {
			asked.add(c);
			return asked.size() <= 3;
		});
		assertThat(asked).containsExactlyElementsOf(draws);
		assertThat(code).isEqualTo(draws.get(3));
	}

	@Test
	void alwaysTakenThrowsAfterOneHundredTries() {
		int[] tries = {0};
		assertThatThrownBy(() -> RoomCodes.next(new Random(1), c -> {
			tries[0]++;
			return true;
		})).isInstanceOf(IllegalStateException.class);
		assertThat(tries[0]).isEqualTo(100);
	}

}
