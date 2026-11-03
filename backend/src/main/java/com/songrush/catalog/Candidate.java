package com.songrush.catalog;

// A draw-set theme whose audio is cached (D-056).
public record Candidate(int themeId, String audioBasename, long durationMs) {
}
