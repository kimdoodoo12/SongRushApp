package com.songrush.feedback;

// The issue was not created. The message names only the cause type or the GitHub status:
// never the token or the user's text.
public class IssueSendException extends Exception {

	private static final long serialVersionUID = 1L;

	public IssueSendException(String message) {
		super(message);
	}

}
