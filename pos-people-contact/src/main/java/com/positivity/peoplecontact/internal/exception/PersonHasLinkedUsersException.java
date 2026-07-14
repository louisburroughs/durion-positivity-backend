package com.positivity.peoplecontact.internal.exception;

import java.util.UUID;

/**
 * A person cannot be hard-deleted while {@code user_person_links} rows still reference it
 * (#881). Surfaced as 409 CONFLICT with a {@code nextAction} instead of letting the FK
 * violation escape as a 500 (ADR-0017 response-code matrix).
 */
public class PersonHasLinkedUsersException extends RuntimeException {

    public static final String NEXT_ACTION = "Unlink the person's user accounts, then retry the delete.";

    public PersonHasLinkedUsersException(UUID personId) {
        super("Person " + personId + " still has linked user accounts and cannot be deleted");
    }
}
