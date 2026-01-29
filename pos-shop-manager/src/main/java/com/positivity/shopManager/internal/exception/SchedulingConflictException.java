package com.positivity.shopManager.internal.exception;

import com.positivity.shopManager.internal.dto.ConflictResponse;

/**
 * Thrown when scheduling conflicts are detected during appointment creation.
 * Mapped to HTTP 409 (Conflict) response.
 * Contains detailed conflict information to be returned to client.
 */
public class SchedulingConflictException extends RuntimeException {
    private final ConflictResponse conflictResponse;

    public SchedulingConflictException(ConflictResponse conflictResponse) {
        super("Scheduling conflict detected: " + conflictResponse.getMessage());
        this.conflictResponse = conflictResponse;
    }

    public ConflictResponse getConflictResponse() {
        return conflictResponse;
    }
}
