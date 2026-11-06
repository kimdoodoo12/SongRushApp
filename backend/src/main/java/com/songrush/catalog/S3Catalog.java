package com.songrush.catalog;

import java.util.Map;

// The catalog in S3 mode, and the object key of every candidate's basename (cycle 074).
public record S3Catalog(Catalog catalog, Map<String, String> keyByBasename) {

	public S3Catalog {
		keyByBasename = Map.copyOf(keyByBasename);
	}

	// Counts only: basenames reveal answers and keys locate the audio.
	@Override
	public String toString() {
		return "S3Catalog[candidates=" + catalog.candidates().size() + ", keys=" + keyByBasename.size() + "]";
	}

}
