package com.songrush;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

// Binds Path properties with plain Path.of. Without it, binding falls back to Spring's PathEditor,
// which resolves the text through the servlet context's resource loader; Tomcat rejects "../data".
@Component
@ConfigurationPropertiesBinding
public class StringToPathConverter implements Converter<String, Path> {

	@Override
	public Path convert(String source) {
		return Path.of(source);
	}

}
