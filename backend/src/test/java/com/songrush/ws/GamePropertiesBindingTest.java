package com.songrush.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.songrush.GameProperties;

// Cycle 107 (D-119) item 1: the switch songrush.game.prefetch-on-reveal (env
// SONGRUSH_GAME_PREFETCHONREVEAL), default false (docs/cycles/107-prefetch-on-reveal.md).
//
// API this test pins (new):
//   package com.songrush;
//   @ConfigurationProperties(prefix = "songrush.game")
//   public record GameProperties(boolean prefetchOnReveal) { }
// found by the existing @ConfigurationPropertiesScan, and injected into GameSocketHandler, which passes
// prefetchOnReveal to every Lobby it makes (PrefetchOnRevealSocketTest checks that wiring end to end).
class GamePropertiesBindingTest {

	@Test
	void bindsFromTheKebabCaseProperty() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.game.prefetch-on-reveal", "true")));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().prefetchOnReveal()).isTrue();
	}

	@Test
	void bindsFromTheEnvironmentVariable() {
		SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("systemEnvironment",
				Map.of("SONGRUSH_GAME_PREFETCHONREVEAL", "true"));
		Binder binder = new Binder(ConfigurationPropertySources.from(env));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().prefetchOnReveal()).isTrue();
	}

	@Test
	void falseBindsFalse() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.game.prefetch-on-reveal", "false")));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().prefetchOnReveal()).isFalse();
	}

	@Test
	void absentMeansOff() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.data-dir", "x")));
		assertThat(binder.bindOrCreate("songrush.game", GameProperties.class).prefetchOnReveal()).isFalse();
	}

}
