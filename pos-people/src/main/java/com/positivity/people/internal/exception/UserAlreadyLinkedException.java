package com.positivity.people.internal.exception;

import java.util.UUID;

public class UserAlreadyLinkedException extends RuntimeException {

    public UserAlreadyLinkedException(String username) {
        super("User " + username + " is already linked to a person");
    }

    public UserAlreadyLinkedException(String username, UUID currentPersonId, UUID requestedPersonId) {
        super("User " + username + " is already linked to person " + currentPersonId
                + " and cannot be linked to person " + requestedPersonId);
    }
}
