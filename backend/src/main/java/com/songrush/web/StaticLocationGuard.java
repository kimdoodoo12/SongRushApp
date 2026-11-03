package com.songrush.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.songrush.SongrushProperties;

// Fails startup when a file: static location and songrush.data-dir are equal or one contains the
// other, so the web folder can never serve catalog.json or the audio cache (cycle 054, review H1).
// Containment is by path components, so a sibling data-web next to data is fine. Non-file locations
// (Boot's classpath: defaults) are ignored.
@Component
public class StaticLocationGuard {

	static final String LOCATIONS_PROPERTY = "spring.web.resources.static-locations";

	private static final String FILE_PREFIX = "file:";

	public StaticLocationGuard(Environment environment, ResourceLoader resourceLoader,
			SongrushProperties properties) {
		Path dataDir = properties.dataDir().toAbsolutePath().normalize();
		String[] locations = Binder.get(environment).bind(LOCATIONS_PROPERTY, String[].class).orElse(new String[0]);
		for (String location : locations) {
			String trimmed = location.trim();
			if (!trimmed.startsWith(FILE_PREFIX)) {
				continue;
			}
			Path staticDir = resolve(resourceLoader, trimmed);
			if (staticDir.startsWith(dataDir) || dataDir.startsWith(staticDir)) {
				throw new IllegalStateException("static location " + staticDir + " (" + trimmed
						+ ") and songrush.data-dir " + dataDir
						+ " must not be equal or contain each other; put the web files in a separate folder");
			}
		}
	}

	private static Path resolve(ResourceLoader resourceLoader, String location) {
		try {
			return resourceLoader.getResource(location).getFile().toPath().toAbsolutePath().normalize();
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot resolve static location " + location + " to a file", e);
		}
	}

}
