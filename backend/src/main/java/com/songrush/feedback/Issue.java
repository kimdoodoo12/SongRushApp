package com.songrush.feedback;

import java.util.List;

// One GitHub issue to create: the JSON body of POST /repos/{repo}/issues (cycle 082).
public record Issue(String title, String body, List<String> labels) {

	public Issue {
		labels = List.copyOf(labels);
	}

}
