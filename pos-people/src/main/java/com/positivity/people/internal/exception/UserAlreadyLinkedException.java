package com.positivity.people.internal.exception;

import java.util.UUID;

public class UserAlreadyLinkedException extends RuntimeException {
    public UserAlreadyLinkedException(UUID userId) {
        super("User " + userId + " is already linked to a person");
    }
}
