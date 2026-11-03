package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Cycle 060 (B-011) criterion 4: CatalogLoader reads each anime's "year" (null when absent) and each
// theme's "type" as before (docs/cycles/060-draw-filters.md). The fixture catalog is the one under
// src/test/resources/catalog/ (CatalogLoaderTest.FIXTURE); the absent-year case is written into a
// temp folder.
class CatalogYearTest {

	@TempDir
	Path dir;

	private Catalog fixture() {
		return CatalogLoader.load(CatalogLoaderTest.FIXTURE, CatalogLoaderTest.noIndex(dir));
	}

	@Test
	void yearIsReadFromTheFixtureAnime() {
		Catalog catalog = fixture();
		assertThat(catalog.anime(1).orElseThrow().year()).isEqualTo(1998);
		assertThat(catalog.anime(2).orElseThrow().year()).isEqualTo(2006);
		assertThat(catalog.anime(3).orElseThrow().year()).isEqualTo(1990);
	}

	@Test
	void typeIsReadAsBefore() {
		Catalog catalog = fixture();
		assertThat(catalog.theme(10).orElseThrow().type()).isEqualTo("OP");
		assertThat(catalog.theme(11).orElseThrow().type()).isEqualTo("ED");
		assertThat(catalog.theme(20).orElseThrow().type()).isEqualTo("OP");
		assertThat(catalog.theme(30).orElseThrow().type()).isEqualTo("OP");
	}

	@Test
	void anAbsentYearIsNull() throws IOException {
		String json = """
				{
				  "version": 1,
				  "anime": [
				    {"id": 1, "group": 1, "titles": {"en": ["With Year"]}, "reveal": "With Year", "year": 2012},
				    {"id": 2, "group": 2, "titles": {"en": ["No Year"]}, "reveal": "No Year"}
				  ],
				  "groups": [
				    {"id": 1, "accepted": ["withyear"]},
				    {"id": 2, "accepted": ["noyear"]}
				  ],
				  "themes": [
				    {"id": 10, "animeId": 1, "type": "OP", "slug": "OP1", "song": "A", "artists": [],
				      "audio": {"basename": "WithYear-OP1.ogg", "size": 10}},
				    {"id": 20, "animeId": 2, "type": "ED", "slug": "ED1", "song": "B", "artists": [],
				      "audio": {"basename": "NoYear-ED1.ogg", "size": 11}}
				  ],
				  "drawSet": [10, 20]
				}
				""";
		Path catalogJson = dir.resolve("catalog.json");
		Files.writeString(catalogJson, json, StandardCharsets.UTF_8);
		Catalog catalog = CatalogLoader.load(catalogJson, CatalogLoaderTest.noIndex(dir));
		assertThat(catalog.anime(1).orElseThrow().year()).isEqualTo(2012);
		assertThat(catalog.anime(2).orElseThrow().year()).isNull();
		assertThat(catalog.theme(10).orElseThrow().type()).isEqualTo("OP");
		assertThat(catalog.theme(20).orElseThrow().type()).isEqualTo("ED");
	}

}
