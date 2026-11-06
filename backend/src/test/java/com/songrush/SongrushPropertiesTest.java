package com.songrush;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// Cycle 012 criterion 4: songrush.data-dir binds to SongrushProperties.dataDir(),
// defaulting to ../data (relative to backend/, the working directory under Gradle).
class SongrushPropertiesTest {

	@Nested
	@SpringBootTest
	class Default {

		@Autowired
		private SongrushProperties properties;

		@Test
		void dataDirDefaultsToParentData() {
			assertThat(properties.dataDir()).isEqualTo(Path.of("../data"));
		}
	}

	@Nested
	@SpringBootTest(properties = "songrush.data-dir=src/test/resources/catalog")
	class Override {

		@Autowired
		private SongrushProperties properties;

		@Test
		void dataDirTakesConfiguredValue() {
			assertThat(properties.dataDir()).isEqualTo(Path.of("src/test/resources/catalog"));
		}
	}
}
