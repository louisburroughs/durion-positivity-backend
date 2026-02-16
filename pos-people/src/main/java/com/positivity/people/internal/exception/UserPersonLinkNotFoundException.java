package com.positivity.people.internal.exception;

public class UserPersonLinkNotFoundException extends RuntimeException {

    public UserPersonLinkNotFoundException(String userId) {
        super("No person link found for user: " + userId);
    }
}
