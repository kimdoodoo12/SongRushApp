package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Cycle 033 criterion 5: toString of the fixture's records prints ids only, never answers.
class RecordToStringTest {

	private static final Path FIXTURE = Path.of("src/test/resources/catalog/catalog.json");

	@TempDir
	Path dir;

	private Catalog catalog;

	@BeforeEach
	void load() throws IOException {
		Path cache = dir.resolve("audio-cache");
		Files.createDirectories(cache);
		Files.writeString(cache.resolve("index.json"),
				"{\"10\": {\"basename\": \"CowboyBebop-OP1.ogg\", \"size\": 101, \"durationMs\": 89000}}",
				StandardCharsets.UTF_8);
		Files.write(cache.resolve("CowboyBebop-OP1.ogg"), new byte[101]);
		catalog = CatalogLoader.load(FIXTURE, cache.resolve("index.json"));
	}

	private static void assertHidden(String text, List<String> secrets) {
		for (String secret : secrets) {
			assertThat(text).doesNotContainIgnoringCase(secret);
		}
	}

	@Test
	void animeToStringHasTheIdAndNoTitlesOrReveal() {
		Anime anime = catalog.anime(1).orElseThrow();
		String text = anime.toString();
		assertThat(text).contains("1");
		List<String> secrets = new ArrayList<>();
		anime.titles().values().forEach(secrets::addAll);
		secrets.add(anime.reveal());
		assertThat(secrets).contains("Cowboy Bebop", "カウボーイビバップ", "카우보이 비밥");
		assertHidden(text, secrets);

		Anime other = catalog.anime(2).orElseThrow();
		assertThat(other.toString()).contains("2");
		assertHidden(other.toString(), List.of("Death Note", "デスノート", "데스노트"));
	}

	@Test
	void themeToStringHasTheIdAndNoSongArtistsOrBasename() {
		Theme theme = catalog.theme(11).orElseThrow();
		String text = theme.toString();
		assertThat(text).contains("11");
		List<String> secrets = new ArrayList<>(theme.artists());
		secrets.add(theme.song());
		secrets.add(theme.audioBasename());
		assertThat(secrets).contains("The Real Folk Blues", "Mai Yamane", "The Seatbelts", "CowboyBebop-ED1.ogg");
		assertHidden(text, secrets);
	}

	@Test
	void candidateToStringHasTheIdAndNoBasename() {
		assertThat(catalog.candidates()).hasSize(1);
		Candidate candidate = catalog.candidates().get(0);
		String text = candidate.toString();
		assertThat(text).contains("10");
		assertHidden(text, List.of("CowboyBebop-OP1.ogg", "CowboyBebop"));
	}

}
