package com.positivity.people.internal.exception;

import java.util.UUID;

public class UserAlreadyLinkedException extends RuntimeException {

    public UserAlreadyLinkedException(UUID userId) {
        super("User " + userId + " is already linked to a person");
    }

    public UserAlreadyLinkedException(UUID userId, UUID currentPersonId, UUID requestedPersonId) {
        super("User " + userId + " is already linked to person " + currentPersonId
                + " and cannot be linked to person " + requestedPersonId);
    }
}
