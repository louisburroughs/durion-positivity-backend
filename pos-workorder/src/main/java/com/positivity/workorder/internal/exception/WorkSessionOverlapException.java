package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * Thrown when a mechanic already has an active work session and no override reason is supplied.
 */
public class WorkSessionOverlapException extends RuntimeException {

    public WorkSessionOverlapException(UUID mechanicId) {
        super("Mechanic " + mechanicId + " has an active work session. Use overlapOverrideReason to override.");
    }
}
