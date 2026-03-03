package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * Thrown when a {@code WorkSession} or {@code BreakSegment} cannot be found by
 * its ID.
 */
public class WorkSessionNotFoundException extends RuntimeException {

    public WorkSessionNotFoundException(UUID id) {
        super("WorkSession not found: " + id);
    }
}
