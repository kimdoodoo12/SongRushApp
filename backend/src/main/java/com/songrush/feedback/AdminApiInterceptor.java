package com.songrush.feedback;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// The authoritative lock on the admin API (cycle 083, D-097), decided by the matched handler (an
// AdminFeedbackController method), never by the URI text, so /admin;x/api/... variants are covered.
// Nginx Basic Auth is the first lock; nginx adds X-Admin-Key only inside location /admin.
// 1. Cache-Control: no-store on every response, refusals included.
// 2. The X-Admin-Key header must equal songrush.admin.key (SHA-256 digests compared in constant time);
//    a blank key, a missing header or a wrong value answers the app's plain JSON 404, with no hint.
// 3. Every request other than GET/HEAD also needs X-Requested-With: songrush-admin and a JSON media
//    type, else 403 {"error":"forbidden"}. A custom header forces a CORS preflight, which the app never
//    allows, so another site cannot drive the owner's browser.
final class AdminApiInterceptor implements HandlerInterceptor {

	static final String KEY_HEADER = "X-Admin-Key";

	static final String XRW_HEADER = "X-Requested-With";

	static final String XRW_VALUE = "songrush-admin";

	private final AdminProperties properties;

	AdminApiInterceptor(AdminProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws IOException {
		if (!(handler instanceof HandlerMethod method && method.getBeanType() == AdminFeedbackController.class)) {
			return true;
		}
		response.setHeader("Cache-Control", "no-store");
		if (!keyMatches(request.getHeader(KEY_HEADER))) {
			refuse(response, HttpServletResponse.SC_NOT_FOUND, "not found");
			return false;
		}
		String verb = request.getMethod();
		boolean reading = HttpMethod.GET.matches(verb) || HttpMethod.HEAD.matches(verb);
		if (!reading && !(XRW_VALUE.equals(request.getHeader(XRW_HEADER)) && isJson(request.getContentType()))) {
			refuse(response, HttpServletResponse.SC_FORBIDDEN, "forbidden");
			return false;
		}
		return true;
	}

	private boolean keyMatches(String given) {
		String key = properties.key();
		if (key.isEmpty() || given == null) {
			return false;
		}
		return MessageDigest.isEqual(sha256(key), sha256(given));
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			// Every Java platform must provide SHA-256.
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static boolean isJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
		}
		catch (InvalidMediaTypeException e) {
			return false;
		}
	}

	// The same body JsonErrorController gives any unknown path, so a refusal reveals nothing.
	private static void refuse(HttpServletResponse response, int status, String error) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"error\":\"" + error + "\"}");
	}

}
