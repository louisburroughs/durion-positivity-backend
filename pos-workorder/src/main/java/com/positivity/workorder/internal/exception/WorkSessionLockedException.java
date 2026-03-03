package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * Thrown when a modification is attempted on a locked {@code WorkSession}.
 */
public class WorkSessionLockedException extends RuntimeException {

    public WorkSessionLockedException(UUID workSessionId) {
        super("WorkSession " + workSessionId + " is locked and cannot be modified.");
    }
}
