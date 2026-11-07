package com.songrush.feedback;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Registers AdminApiInterceptor for every path: it selects the admin handlers itself, by handler type,
// so no URI pattern decides whether the key is checked (cycle 083, D-097).
@Configuration
public class AdminWebConfiguration implements WebMvcConfigurer {

	private final AdminProperties properties;

	public AdminWebConfiguration(AdminProperties properties) {
		this.properties = properties;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AdminApiInterceptor(properties));
	}

}
