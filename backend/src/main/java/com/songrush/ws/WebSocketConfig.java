package com.songrush.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

// Registers the game socket at /ws. No container settings: Tomcat's default 8192-character text
// buffer lets frames over 4096 bytes reach the handler, which answers too-large (spec review B1).
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final GameSocketHandler handler;

	public WebSocketConfig(GameSocketHandler handler) {
		this.handler = handler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws");
	}

}
