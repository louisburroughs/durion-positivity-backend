package com.positivity.people.internal.exception;

public class UserAlreadyLinkedException extends RuntimeException {
    public UserAlreadyLinkedException(String userId) {
        super("User " + userId + " is already linked to a person");
    }
}
