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
//   public record GameProperties(boolean prefetchOnReveal, boolean playToLimit) { }
// found by the existing @ConfigurationPropertiesScan, and injected into GameSocketHandler, which passes
// prefetchOnReveal to every Lobby it makes (PrefetchOnRevealSocketTest checks that wiring end to end).
//
// Cycle 126 (B-034, D-129) criterion 11: the second component playToLimit is the switch
// songrush.game.play-to-limit (env SONGRUSH_GAME_PLAYTOLIMIT), default false
// (docs/cycles/126-reveal-window.md); PlayToLimitSocketTest checks its wiring end to end.
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

	// ---- cycle 126: play-to-limit ----

	@Test
	void playToLimitBindsFromTheKebabCaseProperty() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.game.play-to-limit", "true")));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().playToLimit()).isTrue();
	}

	@Test
	void playToLimitBindsFromTheEnvironmentVariable() {
		SystemEnvironmentPropertySource env = new SystemEnvironmentPropertySource("systemEnvironment",
				Map.of("SONGRUSH_GAME_PLAYTOLIMIT", "true"));
		Binder binder = new Binder(ConfigurationPropertySources.from(env));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().playToLimit()).isTrue();
	}

	@Test
	void playToLimitFalseBindsFalse() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.game.play-to-limit", "false")));
		assertThat(binder.bind("songrush.game", GameProperties.class).get().playToLimit()).isFalse();
	}

	@Test
	void playToLimitAbsentMeansOff() {
		Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("songrush.data-dir", "x")));
		assertThat(binder.bindOrCreate("songrush.game", GameProperties.class).playToLimit()).isFalse();
	}

	@Test
	void theTwoSwitchesBindIndependently() {
		Binder limitOnly = new Binder(new MapConfigurationPropertySource(Map.of("songrush.game.play-to-limit", "true")));
		GameProperties limit = limitOnly.bind("songrush.game", GameProperties.class).get();
		assertThat(limit.playToLimit()).isTrue();
		assertThat(limit.prefetchOnReveal()).isFalse();

		Binder prefetchOnly = new Binder(
				new MapConfigurationPropertySource(Map.of("songrush.game.prefetch-on-reveal", "true")));
		GameProperties prefetch = prefetchOnly.bind("songrush.game", GameProperties.class).get();
		assertThat(prefetch.prefetchOnReveal()).isTrue();
		assertThat(prefetch.playToLimit()).isFalse();

		Binder both = new Binder(new MapConfigurationPropertySource(
				Map.of("songrush.game.prefetch-on-reveal", "true", "songrush.game.play-to-limit", "true")));
		GameProperties on = both.bind("songrush.game", GameProperties.class).get();
		assertThat(on.prefetchOnReveal()).isTrue();
		assertThat(on.playToLimit()).isTrue();
	}

}
