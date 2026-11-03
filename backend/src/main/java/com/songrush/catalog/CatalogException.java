package com.songrush.catalog;

// The catalog or the audio index cannot be loaded; the message names the file and the reason.
public class CatalogException extends RuntimeException {

	public CatalogException(String message) {
		super(message);
	}

	public CatalogException(String message, Throwable cause) {
		super(message, cause);
	}

}
