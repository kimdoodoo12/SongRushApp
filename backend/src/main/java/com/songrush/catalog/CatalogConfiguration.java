package com.songrush.catalog;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.songrush.SongrushProperties;

// Loads the Catalog bean from songrush.data-dir at startup; a CatalogException fails startup.
@Configuration
public class CatalogConfiguration {

	private static final Logger log = LoggerFactory.getLogger(CatalogConfiguration.class);

	@Bean
	public Catalog catalog(SongrushProperties properties) {
		Path catalogJson = properties.dataDir().resolve("catalog.json");
		Path indexJson = properties.dataDir().resolve("audio-cache").resolve("index.json");
		Catalog catalog = CatalogLoader.load(catalogJson, indexJson);
		log.info("catalog loaded: {} anime, {} themes, {} draw set, {} candidates from {}", catalog.animeCount(),
				catalog.themeCount(), catalog.drawSet().size(), catalog.candidates().size(), catalogJson);
		return catalog;
	}

}
