package com.positivity.peoplecontact.internal.exception;

import java.util.UUID;

public class UserPersonLinkNotFoundException extends RuntimeException {

    public UserPersonLinkNotFoundException(String username) {
        super("No person link found for user: " + username);
    }

    public UserPersonLinkNotFoundException(UUID personId) {
        super("No person link found for person: " + personId);
    }
}
