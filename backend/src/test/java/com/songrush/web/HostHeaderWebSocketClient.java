package com.songrush.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

// Spring's StandardWebSocketClient, but the headers reach Tomcat's upgrade request as given. The
// public execute(handler, headers, uri) drops "Host" (AbstractWebSocketClient.specialHeaders in
// spring-websocket 7.0.x); executeInternal does not filter, and the configurator then puts each key
// into Tomcat's request map, so a key of exactly "Host" replaces the one Tomcat derived from the URI.
final class HostHeaderWebSocketClient extends StandardWebSocketClient {

	CompletableFuture<WebSocketSession> connect(WebSocketHandler handler, WebSocketHttpHeaders headers, URI uri) {
		return executeInternal(handler, headers, uri, List.of(), List.of(), Map.of());
	}

}
