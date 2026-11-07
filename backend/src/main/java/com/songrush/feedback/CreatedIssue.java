package com.songrush.feedback;

// The issue GitHub created: its number and its html_url (cycle 083, D-097).
public record CreatedIssue(int number, String url) {

}
