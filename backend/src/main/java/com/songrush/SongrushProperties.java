package com.songrush;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// songrush.data-dir; the default is relative to the working directory (backend/ under Gradle).
@ConfigurationProperties(prefix = "songrush")
public record SongrushProperties(@DefaultValue("../data") Path dataDir) {
}
