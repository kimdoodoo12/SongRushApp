package com.songrush.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;

// songrush.admin.key (env SONGRUSH_ADMIN_KEY, cycle 083, D-097): the second lock on /admin/api behind
// nginx Basic Auth. Trimmed when bound; a blank key turns the admin API off (every request answers 404).
// toString never shows the key.
@ConfigurationProperties(prefix = "songrush.admin")
public record AdminProperties(String key) {

	public AdminProperties {
		key = key == null ? "" : key.strip();
	}

	public boolean enabled() {
		return !key.isEmpty();
	}

	@Override
	public String toString() {
		return "AdminProperties[key=" + (enabled() ? "<set>" : "<empty>") + "]";
	}

}
