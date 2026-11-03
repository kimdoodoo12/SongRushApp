package com.songrush.catalog;

import java.util.List;

// One catalog OP/ED theme. song may be null.
// Holds answers: never serialize it into a response (cycle 032, answer safety).
public record Theme(int id, int animeId, String type, String slug, String song, List<String> artists,
		String audioBasename) {

	public Theme {
		artists = List.copyOf(artists);
	}

}
