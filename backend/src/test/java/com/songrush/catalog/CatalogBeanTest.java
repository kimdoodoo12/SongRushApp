package com.songrush.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Cycle 032 criterion 4: with the default songrush.data-dir (../data) the context starts and the
// Catalog bean matches the committed data/catalog.json, read here independently. No hard-coded
// counts (spec review M2): the catalog is rebuilt from time to time.
@SpringBootTest
class CatalogBeanTest {

	private static final Path COMMITTED = Path.of("../data/catalog.json");

	@Autowired
	private Catalog catalog;

	private static JsonNode committed() {
		return new ObjectMapper().readTree(COMMITTED.toFile());
	}

	private static List<Integer> committedDrawSet() {
		List<Integer> ids = new ArrayList<>();
		for (JsonNode id : committed().get("drawSet")) {
			ids.add(id.asInt());
		}
		return ids;
	}

	@Test
	void countsEqualTheCommittedCatalog() {
		JsonNode root = committed();
		assertThat(catalog.animeCount()).isEqualTo(root.get("anime").size());
		assertThat(catalog.themeCount()).isEqualTo(root.get("themes").size());
		assertThat(catalog.drawSet()).hasSize(root.get("drawSet").size());
	}

	@Test
	void drawSetEqualsTheCommittedOrder() {
		assertThat(catalog.drawSet()).containsExactlyElementsOf(committedDrawSet());
	}

	@Test
	void candidatesAreDrawSetThemesInDrawSetOrder() {
		List<Integer> drawSet = committedDrawSet();
		int previous = -1;
		for (Candidate candidate : catalog.candidates()) {
			int position = drawSet.indexOf(candidate.themeId());
			assertThat(position).as("theme %d is in the draw set", candidate.themeId()).isGreaterThanOrEqualTo(0);
			assertThat(position).as("theme %d keeps the draw-set order", candidate.themeId()).isGreaterThan(previous);
			assertThat(candidate.durationMs()).isPositive();
			previous = position;
		}
	}
}
