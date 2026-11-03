package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Cycle 032 criteria 1-3 and 6: CatalogLoader.load on the fixture under
// src/test/resources/catalog/ (paths relative to backend/, the working directory under Gradle).
class CatalogLoaderTest {

	static final Path FIXTURE = Path.of("src/test/resources/catalog/catalog.json");
	static final Path INVALID = Path.of("src/test/resources/catalog/invalid");

	// A path that does not exist: loading the fixture with it gives zero candidates.
	static Path noIndex(Path dir) {
		return dir.resolve("audio-cache").resolve("index.json");
	}

	@Nested
	class Lookups {

		@TempDir
		Path dir;

		Catalog load() {
			return CatalogLoader.load(FIXTURE, noIndex(dir));
		}

		@Test
		void countsMatchTheFixture() {
			Catalog catalog = load();
			assertThat(catalog.animeCount()).isEqualTo(3);
			assertThat(catalog.themeCount()).isEqualTo(4);
		}

		@Test
		void animeHasItsFieldsIncludingKoreanTitles() {
			Anime anime = load().anime(1).orElseThrow();
			assertThat(anime.id()).isEqualTo(1);
			assertThat(anime.anilistId()).isEqualTo(1);
			assertThat(anime.group()).isEqualTo(1);
			assertThat(anime.reveal()).isEqualTo("카우보이 비밥");
			assertThat(anime.titles()).containsOnlyKeys("ko", "en", "ja", "romaji");
			assertThat(anime.titles().get("ko")).containsExactly("카우보이 비밥");
			assertThat(anime.titles().get("en")).containsExactly("Cowboy Bebop");
			assertThat(anime.titles().get("ja")).containsExactly("カウボーイビバップ");
			assertThat(anime.titles().get("romaji")).containsExactly("Cowboy Bebop");

			Anime other = load().anime(2).orElseThrow();
			assertThat(other.reveal()).isEqualTo("데스노트");
			assertThat(other.titles().get("ko")).containsExactly("데스노트");
		}

		@Test
		void animeNullableFieldsAreNull() {
			Anime anime = load().anime(3).orElseThrow();
			assertThat(anime.anilistId()).isNull();
			assertThat(anime.group()).isNull();
			assertThat(anime.reveal()).isNull();
			assertThat(anime.titles().get("ko")).isEmpty();
			assertThat(anime.titles().get("romaji")).containsExactly("Obscure OVA");
		}

		@Test
		void themeHasItsFields() {
			Theme theme = load().theme(11).orElseThrow();
			assertThat(theme.id()).isEqualTo(11);
			assertThat(theme.animeId()).isEqualTo(1);
			assertThat(theme.type()).isEqualTo("ED");
			assertThat(theme.slug()).isEqualTo("ED1");
			assertThat(theme.song()).isEqualTo("The Real Folk Blues");
			assertThat(theme.artists()).containsExactly("Mai Yamane", "The Seatbelts");
			assertThat(theme.audioBasename()).isEqualTo("CowboyBebop-ED1.ogg");
		}

		@Test
		void themeSongMayBeNull() {
			Theme theme = load().theme(30).orElseThrow();
			assertThat(theme.song()).isNull();
			assertThat(theme.artists()).isEmpty();
			assertThat(theme.audioBasename()).isEqualTo("ObscureOVA-OP1.ogg");
		}

		@Test
		void acceptedReturnsTheGroupStrings() {
			Catalog catalog = load();
			assertThat(catalog.accepted(1)).containsExactly("cowboybebop", "カウボーイビバップ", "카우보이비밥");
			assertThat(catalog.accepted(2)).containsExactly("deathnote", "デスノート", "데스노트");
		}

		@Test
		void unknownIdsGiveEmptyResults() {
			Catalog catalog = load();
			assertThat(catalog.anime(999)).isEmpty();
			assertThat(catalog.theme(999)).isEmpty();
			assertThat(catalog.accepted(999)).isEmpty();
		}

		@Test
		void drawSetKeepsTheFileOrder() {
			assertThat(load().drawSet()).containsExactly(20, 10, 11);
		}

		@Test
		void returnedCollectionsAreUnmodifiable() {
			Catalog catalog = load();
			Anime anime = catalog.anime(1).orElseThrow();
			Theme theme = catalog.theme(10).orElseThrow();

			assertThatThrownBy(() -> anime.titles().put("xx", List.of()))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> anime.titles().get("ko").add("x"))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> theme.artists().add("x"))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> catalog.accepted(1).add("x"))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> catalog.drawSet().add(1))
					.isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> catalog.candidates().add(new Candidate(1, "x.ogg", 1L)))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	class Candidates {

		@TempDir
		Path dir;

		Path catalog;
		Path cache;
		Path index;

		void setUp() throws IOException {
			catalog = dir.resolve("catalog.json");
			Files.copy(FIXTURE, catalog);
			cache = Files.createDirectories(dir.resolve("audio-cache"));
			index = cache.resolve("index.json");
		}

		// entries: {themeId, basename, durationMs, size}
		void writeIndex(Object[]... entries) throws IOException {
			List<String> parts = new ArrayList<>();
			for (Object[] e : entries) {
				parts.add("\"" + e[0] + "\": {\"basename\": \"" + e[1] + "\", \"durationMs\": " + e[2]
						+ ", \"size\": " + e[3] + "}");
			}
			Files.writeString(index, "{" + String.join(", ", parts) + "}", StandardCharsets.UTF_8);
		}

		void writeFile(String basename, int size) throws IOException {
			Files.write(cache.resolve(basename), new byte[size]);
		}

		List<Candidate> candidates() {
			return CatalogLoader.load(catalog, index).candidates();
		}

		@Test
		void cachedDrawSetThemesAreCandidatesInDrawSetOrder() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 85000, 101},
					new Object[] {11, "CowboyBebop-ED1.ogg", 89000, 102},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 102);
			writeFile("DeathNote-OP1.ogg", 103);

			assertThat(candidates()).containsExactly(
					new Candidate(20, "DeathNote-OP1.ogg", 80061L),
					new Candidate(10, "CowboyBebop-OP1.ogg", 85000L),
					new Candidate(11, "CowboyBebop-ED1.ogg", 89000L));
		}

		@Test
		void themeWithoutIndexEntryIsLeftOut() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 85000, 101},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 102);
			writeFile("DeathNote-OP1.ogg", 103);

			assertThat(candidates()).extracting(Candidate::themeId).containsExactly(20, 10);
		}

		@Test
		void themeWhoseFileIsMissingIsLeftOut() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 85000, 101},
					new Object[] {11, "CowboyBebop-ED1.ogg", 89000, 102},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 102);

			assertThat(candidates()).extracting(Candidate::themeId).containsExactly(10, 11);
		}

		@Test
		void themeWhoseIndexBasenameDiffersIsLeftOut() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 85000, 101},
					new Object[] {11, "CowboyBebop-ED1v2.ogg", 89000, 102},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 102);
			writeFile("CowboyBebop-ED1v2.ogg", 102);
			writeFile("DeathNote-OP1.ogg", 103);

			assertThat(candidates()).extracting(Candidate::themeId).containsExactly(20, 10);
		}

		@Test
		void themeWithZeroDurationIsLeftOut() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 0, 101},
					new Object[] {11, "CowboyBebop-ED1.ogg", 89000, 102},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 102);
			writeFile("DeathNote-OP1.ogg", 103);

			assertThat(candidates()).extracting(Candidate::themeId).containsExactly(20, 11);
		}

		@Test
		void themeWhoseFileSizeDiffersIsLeftOut() throws IOException {
			setUp();
			writeIndex(
					new Object[] {10, "CowboyBebop-OP1.ogg", 85000, 101},
					new Object[] {11, "CowboyBebop-ED1.ogg", 89000, 102},
					new Object[] {20, "DeathNote-OP1.ogg", 80061, 103});
			writeFile("CowboyBebop-OP1.ogg", 101);
			writeFile("CowboyBebop-ED1.ogg", 50);
			writeFile("DeathNote-OP1.ogg", 103);

			assertThat(candidates()).extracting(Candidate::themeId).containsExactly(20, 10);
		}

		@Test
		void indexedThemeOutsideTheDrawSetIsNotACandidate() throws IOException {
			setUp();
			writeIndex(new Object[] {30, "ObscureOVA-OP1.ogg", 70000, 104});
			writeFile("ObscureOVA-OP1.ogg", 104);

			assertThat(candidates()).isEmpty();
		}
	}

	@Nested
	class Errors {

		@TempDir
		Path dir;

		@Test
		void missingCatalogThrowsWithPath() {
			Path missing = dir.resolve("nope").resolve("catalog.json");
			assertThatThrownBy(() -> CatalogLoader.load(missing, noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining(missing.toString());
		}

		@Test
		void catalogThatIsNotJsonThrowsWithPath() throws IOException {
			Path broken = dir.resolve("catalog.json");
			Files.writeString(broken, "{ this is not json", StandardCharsets.UTF_8);
			assertThatThrownBy(() -> CatalogLoader.load(broken, noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining(broken.toString());
		}

		@Test
		void catalogWithoutDrawSetThrowsWithPath() {
			Path noDrawSet = INVALID.resolve("no-draw-set.json");
			assertThatThrownBy(() -> CatalogLoader.load(noDrawSet, noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining(noDrawSet.toString());
		}

		@Test
		void catalogExceptionIsARuntimeException() {
			assertThat(RuntimeException.class).isAssignableFrom(CatalogException.class);
		}

		@Test
		void missingIndexGivesZeroCandidates() {
			Catalog catalog = CatalogLoader.load(FIXTURE, noIndex(dir));
			assertThat(catalog.candidates()).isEmpty();
			assertThat(catalog.drawSet()).containsExactly(20, 10, 11);
		}

		@Test
		void indexThatIsNotJsonThrowsWithIndexPath() throws IOException {
			Path cache = Files.createDirectories(dir.resolve("audio-cache"));
			Path index = cache.resolve("index.json");
			Files.writeString(index, "not json at all {", StandardCharsets.UTF_8);
			assertThatThrownBy(() -> CatalogLoader.load(FIXTURE, index))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining(index.toString());
		}
	}

	@Nested
	class Integrity {

		@TempDir
		Path dir;

		@Test
		void drawSetNamingAMissingThemeThrowsWithTheId() {
			assertThatThrownBy(() -> CatalogLoader.load(INVALID.resolve("draw-set-missing-theme.json"), noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining("9901");
		}

		@Test
		void themeNamingAMissingAnimeThrowsWithTheId() {
			assertThatThrownBy(() -> CatalogLoader.load(INVALID.resolve("theme-missing-anime.json"), noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining("9902");
		}

		@Test
		void animeNamingAMissingGroupThrowsWithTheId() {
			assertThatThrownBy(() -> CatalogLoader.load(INVALID.resolve("anime-missing-group.json"), noIndex(dir)))
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining("9903");
		}
	}
}
