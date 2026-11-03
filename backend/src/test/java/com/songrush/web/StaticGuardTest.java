package com.songrush.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import com.songrush.BackendApplication;

// Cycle 054 (B-009) criterion 4 (spec review H1, M3): startup fails when a static location and
// songrush.data-dir are equal or one contains the other, whichever is the outer one; a sibling that
// merely shares a name prefix (data-web next to data) starts. Each case is a real SERVLET application
// on port 0. All folders exist before startup; locations are passed as dir.toUri().toString().
class StaticGuardTest {

	@TempDir
	Path tmp;

	private Path data;

	@BeforeEach
	void writeData() {
		data = tmp.resolve("data");
		WebFixture.writeData(data);
	}

	// Starts the application with the static location; returns the startup failure, or null when it
	// started (the context is then closed).
	private Throwable start(Path staticDir) throws IOException {
		Files.createDirectories(staticDir);
		ConfigurableApplicationContext context = null;
		try {
			context = new SpringApplicationBuilder(BackendApplication.class)
					.web(WebApplicationType.SERVLET)
					.run("--server.port=0",
							"--songrush.data-dir=" + data.toString(),
							"--spring.web.resources.static-locations=" + staticDir.toUri().toString());
			return null;
		}
		catch (RuntimeException e) {
			return e;
		}
		finally {
			if (context != null) {
				context.close();
			}
		}
	}

	private static boolean mentionsStatic(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && message.contains("static")) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private void assertRefused(Path staticDir) throws IOException {
		Throwable failure = start(staticDir);
		assertThat(failure).as("startup with static location %s and data dir %s", staticDir, data).isNotNull();
		assertThat(mentionsStatic(failure)).as("cause chain of %s mentions static", failure).isTrue();
	}

	@Test
	void staticInsideTheDataDirFails() throws IOException {
		assertRefused(data.resolve("web"));
	}

	@Test
	void staticEqualToTheDataDirFails() throws IOException {
		assertRefused(data);
	}

	@Test
	void staticContainingTheDataDirFails() throws IOException {
		assertRefused(tmp);
	}

	@Test
	void siblingWithASharedPrefixStarts() throws IOException {
		Throwable failure = start(tmp.resolve("data-web"));
		assertThat(failure).as("startup with a data-web sibling").isNull();
	}

}
