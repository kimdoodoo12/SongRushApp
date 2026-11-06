package com.songrush;

import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Replaces Boot's BasicErrorController (it is conditional on a missing ErrorController bean),
// so every error is JSON whatever the Accept header, including browsers sending text/html.
@RestController
public class JsonErrorController implements ErrorController {

	@RequestMapping("/error")
	public ResponseEntity<Map<String, String>> error(HttpServletRequest request) {
		HttpStatus status = statusOf(request);
		String message;
		if (status == HttpStatus.NOT_FOUND) {
			message = "not found";
		}
		else if (status == HttpStatus.METHOD_NOT_ALLOWED) {
			message = "method not allowed";
		}
		else {
			message = "error";
		}
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("error", message));
	}

	private static HttpStatus statusOf(HttpServletRequest request) {
		Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		if (code instanceof Integer value) {
			HttpStatus status = HttpStatus.resolve(value);
			if (status != null) {
				return status;
			}
		}
		return HttpStatus.INTERNAL_SERVER_ERROR;
	}

}
