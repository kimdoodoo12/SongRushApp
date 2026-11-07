package com.songrush.feedback;

// Creates an issue in the feedback repository. GitHubIssueSender in production; tests swap in a fake.
public interface IssueSender {

	CreatedIssue send(Issue issue) throws IssueSendException;

}
