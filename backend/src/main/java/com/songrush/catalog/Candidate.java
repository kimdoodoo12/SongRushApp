package com.songrush.catalog;

// A draw-set theme whose audio is cached (D-056).
public record Candidate(int themeId, String audioBasename, long durationMs) {

	// Ids only: the basename reveals the answer (cycle 033).
	@Override
	public String toString() {
		return "Candidate[themeId=" + themeId + "]";
	}

}
