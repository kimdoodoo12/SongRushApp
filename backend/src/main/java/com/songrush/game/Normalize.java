package com.songrush.game;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

// Title folding for matching chat guesses, mirroring data/songrush_data/titles.py normalize
// (docs/cycles/035-java-normalize.md). The source stays ASCII, so non-ASCII characters are built
// from code points.
public final class Normalize {

	// Step 2: explicit mappings after toLowerCase(Locale.ROOT).
	private static final String SHARP_S = String.valueOf((char) 0x00DF);
	private static final String FINAL_SIGMA = String.valueOf((char) 0x03C2);
	private static final String SIGMA = String.valueOf((char) 0x03C3);

	// Step 4: the multiplication sign becomes a plain x.
	private static final String TIMES = String.valueOf((char) 0x00D7);

	private Normalize() {
	}

	public static String normalize(String s) {
		Objects.requireNonNull(s, "s");
		String t = Normalizer.normalize(s, Normalizer.Form.NFKC);
		t = t.toLowerCase(Locale.ROOT).replace(SHARP_S, "ss").replace(FINAL_SIGMA, SIGMA);
		t = Normalizer.normalize(t, Normalizer.Form.NFD);
		t = filter(t, false);
		t = Normalizer.normalize(t, Normalizer.Form.NFC);
		t = t.replace(TIMES, "x");
		t = filter(t, true);
		return Normalizer.normalize(t, Normalizer.Form.NFC);
	}

	// Drops combining marks U+0300-U+036F (step 3) or removed characters (step 5), by code point.
	private static String filter(String s, boolean step5) {
		StringBuilder out = new StringBuilder(s.length());
		s.codePoints().forEach(cp -> {
			boolean drop = step5 ? isRemoved(cp) : (cp >= 0x0300 && cp <= 0x036F);
			if (!drop) {
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	private static boolean isRemoved(int cp) {
		if (cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0) {
			return true; // Hangul fillers
		}
		if ((cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0x180B && cp <= 0x180F) || (cp >= 0xE0100 && cp <= 0xE01EF)) {
			return true; // variation selectors
		}
		switch (Character.getType(cp)) {
		case Character.CONNECTOR_PUNCTUATION:
		case Character.DASH_PUNCTUATION:
		case Character.START_PUNCTUATION:
		case Character.END_PUNCTUATION:
		case Character.INITIAL_QUOTE_PUNCTUATION:
		case Character.FINAL_QUOTE_PUNCTUATION:
		case Character.OTHER_PUNCTUATION:
		case Character.MATH_SYMBOL:
		case Character.CURRENCY_SYMBOL:
		case Character.MODIFIER_SYMBOL:
		case Character.OTHER_SYMBOL:
		case Character.SPACE_SEPARATOR:
		case Character.LINE_SEPARATOR:
		case Character.PARAGRAPH_SEPARATOR:
		case Character.CONTROL:
		case Character.FORMAT:
			return true;
		default:
			return false;
		}
	}

}
