package com.songrush;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	@GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Boolean> health() {
		return Map.of("ok", true);
	}

}
