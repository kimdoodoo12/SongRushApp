package com.songrush.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Cycle 083 backend item 1 (D-097): the admin key.
//
// Production API this test relies on (package com.songrush.feedback):
//   @ConfigurationProperties(prefix = "songrush.admin")
//   public record AdminProperties(String key)
//     - picked up by the existing @ConfigurationPropertiesScan; env SONGRUSH_ADMIN_KEY binds to it;
//     - the canonical constructor trims the key (null becomes ""); a blank key turns the admin API off;
//     - toString() is overridden and never contains the key.
//   Binding through Spring (with surrounding spaces) is covered by AdminFeedbackControllerTest.
class AdminPropertiesTest {

	@Test
	void toStringHidesKey() {
		AdminProperties properties = new AdminProperties("adm1n-secret");
		assertThat(properties.toString()).doesNotContain("adm1n-secret");
		assertThat(properties.key()).isEqualTo("adm1n-secret");
	}

	@Test
	void keyIsTrimmed() {
		assertThat(new AdminProperties("  adm1n-secret\r\n").key()).isEqualTo("adm1n-secret");
	}

	@Test
	void blankOrNullKeyBecomesEmpty() {
		assertThat(new AdminProperties(" \r\n ").key()).isEmpty();
		assertThat(new AdminProperties(null).key()).isEmpty();
	}

}
