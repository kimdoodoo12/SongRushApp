package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Cycle 074 criterion 11 (review M4, H2): S3CatalogLoader.load(catalog, index, served map) on the
// fixture catalog (draw set [20, 10, 11]). A candidate needs a valid index entry and a served-map
// entry of the index size; no local audio file is ever written here.
class S3CatalogLoaderTest {

	static final Path FIXTURE = Path.of("src/test/resources/catalog/catalog.json");

	static final String KEY_10 = "a/Q7mZ2xLp9sVb4nWk1rTy0A";

	static final String KEY_11 = "a/H3jK8dFq5wEr6tYu2iOp9Z";

	static final String KEY_20 = "a/T5rE2wQ9aS8dF1gH4jK7lZ";

	static final String KEY_OTHER = "a/P0oI9uY8tR7eW6qA5sD4fG";

	static final String INDEX_ALL = """
			{
			  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000},
			  "11": {"basename": "CowboyBebop-ED1.ogg", "size": 102, "durationMs": 90000},
			  "20": {"basename": "DeathNote-OP1.ogg", "size": 103, "durationMs": 88000}
			}
			""";

	@TempDir
	Path dir;

	Path cache;

	Path index;

	Path servedMap;

	@BeforeEach
	void setUp() throws IOException {
		cache = dir.resolve("audio-cache");
		Files.createDirectories(cache);
		index = cache.resolve("index.json");
		servedMap = cache.resolve("s3-keys.json");
	}

	void write(Path path, String json) throws IOException {
		Files.writeString(path, json, StandardCharsets.UTF_8);
	}

	static String entry(String basename, String key, long size) {
		return "\"" + basename + "\": {\"key\": \"" + key + "\", \"size\": " + size + "}";
	}

	static String map(String... entries) {
		return "{" + String.join(", ", entries) + "}";
	}

	S3Catalog load() {
		return S3CatalogLoader.load(FIXTURE, index, servedMap);
	}

	static List<Integer> ids(S3Catalog loaded) {
		return loaded.catalog().candidates().stream().map(Candidate::themeId).toList();
	}

	@Nested
	class Candidates {

		@Test
		void servedThemesAreCandidatesInDrawSetOrderWithoutLocalFiles() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("CowboyBebop-ED1.ogg", KEY_11, 102),
					entry("DeathNote-OP1.ogg", KEY_20, 103)));

			S3Catalog loaded = load();

			assertThat(loaded.catalog().candidates()).containsExactly(
					new Candidate(20, "DeathNote-OP1.ogg", 88000), new Candidate(10, "CowboyBebop-OP1.ogg", 89000),
					new Candidate(11, "CowboyBebop-ED1.ogg", 90000));
			assertThat(loaded.keyByBasename()).containsEntry("CowboyBebop-OP1.ogg", KEY_10)
					.containsEntry("CowboyBebop-ED1.ogg", KEY_11)
					.containsEntry("DeathNote-OP1.ogg", KEY_20);
			try (var files = Files.list(cache)) {
				assertThat(files.map(p -> p.getFileName().toString()).toList())
						.containsExactlyInAnyOrder("index.json", "s3-keys.json");
			}
		}

		@Test
		void theCatalogItselfIsLoaded() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101)));
			Catalog catalog = load().catalog();
			assertThat(catalog.animeCount()).isEqualTo(3);
			assertThat(catalog.themeCount()).isEqualTo(4);
			assertThat(catalog.drawSet()).containsExactly(20, 10, 11);
		}

		@Test
		void themeWithoutServedMapEntryIsDropped() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("DeathNote-OP1.ogg", KEY_20, 103)));
			S3Catalog loaded = load();
			assertThat(ids(loaded)).containsExactly(20, 10);
			assertThat(loaded.catalog().candidate(11)).isEmpty();
		}

		@Test
		void themeWhoseServedSizeDiffersIsDropped() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("CowboyBebop-ED1.ogg", KEY_11, 999),
					entry("DeathNote-OP1.ogg", KEY_20, 103)));
			assertThat(ids(load())).containsExactly(20, 10);
		}

		@Test
		void themeWithoutIndexEntryIsDropped() throws IOException {
			write(index, """
					{"10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000}}
					""");
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("DeathNote-OP1.ogg", KEY_20, 103)));
			assertThat(ids(load())).containsExactly(10);
		}

		@Test
		void themeWithABadIndexEntryIsDropped() throws IOException {
			write(index, """
					{
					  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000},
					  "11": {"basename": "CowboyBebop-ED1.ogg", "size": 102, "durationMs": 0},
					  "20": {"basename": "SomethingElse.ogg", "size": 103, "durationMs": 88000}
					}
					""");
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("CowboyBebop-ED1.ogg", KEY_11, 102),
					entry("DeathNote-OP1.ogg", KEY_20, 103), entry("SomethingElse.ogg", KEY_OTHER, 103)));
			assertThat(ids(load())).containsExactly(10);
		}

		@Test
		void indexedThemeOutsideTheDrawSetIsNotACandidate() throws IOException {
			write(index, """
					{
					  "10": {"basename": "CowboyBebop-OP1.ogg", "size": 101, "durationMs": 89000},
					  "30": {"basename": "ObscureOVA-OP1.ogg", "size": 104, "durationMs": 85000}
					}
					""");
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101), entry("ObscureOVA-OP1.ogg", KEY_OTHER, 104)));
			assertThat(ids(load())).containsExactly(10);
		}

	}

	@Nested
	class Failures {

		@Test
		void zeroCandidatesWithAnIndexThrows() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 1), entry("CowboyBebop-ED1.ogg", KEY_11, 2)));
			assertThatThrownBy(() -> load()).isInstanceOf(CatalogException.class);
		}

		@Test
		void emptyServedMapThrows() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, "{}");
			assertThatThrownBy(() -> load()).isInstanceOf(CatalogException.class);
		}

		@Test
		void missingServedMapThrowsWithPath() throws IOException {
			write(index, INDEX_ALL);
			assertThatThrownBy(() -> load()).isInstanceOf(CatalogException.class)
					.hasMessageContaining(servedMap.toString());
		}

		@Test
		void missingIndexThrows() throws IOException {
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101)));
			assertThatThrownBy(() -> load()).isInstanceOf(CatalogException.class);
		}

		@Test
		void missingCatalogThrows() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("CowboyBebop-OP1.ogg", KEY_10, 101)));
			assertThatThrownBy(() -> S3CatalogLoader.load(dir.resolve("absent.json"), index, servedMap))
					.isInstanceOf(CatalogException.class);
		}

		// Each invalid kind is the entry of a basename that would otherwise be a candidate, next to a valid
		// entry for theme 20, so ignoring the bad entry would still leave a candidate and not throw.
		@ParameterizedTest
		@ValueSource(strings = {
				// bad JSON, and roots that are not an object
				"{ not json",
				"[]",
				"\"a/Q7mZ2xLp9sVb4nWk1rTy0A\"",
				// a non-object entry
				"{\"CowboyBebop-OP1.ogg\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", @20@}",
				"{\"CowboyBebop-OP1.ogg\": null, @20@}",
				"{\"CowboyBebop-OP1.ogg\": [\"a/Q7mZ2xLp9sVb4nWk1rTy0A\", 101], @20@}",
				// a key not starting with a/
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"b/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": 101}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": 101}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"\", \"size\": 101}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": 7, \"size\": 101}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"size\": 101}, @20@}",
				// a size that is not a positive integer
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": 0}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": -101}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": 101.5}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": \"101\"}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\", \"size\": null}, @20@}",
				"{\"CowboyBebop-OP1.ogg\": {\"key\": \"a/Q7mZ2xLp9sVb4nWk1rTy0A\"}, @20@}" })
		void invalidServedMapThrowsWithPath(String json) throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, json.replace("@20@", entry("DeathNote-OP1.ogg", KEY_20, 103)));
			assertThatThrownBy(() -> load()).as("served map %s", json)
					.isInstanceOf(CatalogException.class)
					.hasMessageContaining(servedMap.toString());
		}

		// The control for the invalid cases: the same valid entry for theme 20 alone loads.
		@Test
		void validEntryAloneLoads() throws IOException {
			write(index, INDEX_ALL);
			write(servedMap, map(entry("DeathNote-OP1.ogg", KEY_20, 103)));
			assertThat(ids(load())).containsExactly(20);
		}

	}

}
