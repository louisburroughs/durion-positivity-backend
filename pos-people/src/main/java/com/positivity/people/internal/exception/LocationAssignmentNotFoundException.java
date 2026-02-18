package com.positivity.people.internal.exception;

import java.util.UUID;

public class LocationAssignmentNotFoundException extends RuntimeException {

    public LocationAssignmentNotFoundException(UUID locationId, UUID personId) {
        super("Assignment not found for location " + locationId + " and person " + personId);
    }
}
