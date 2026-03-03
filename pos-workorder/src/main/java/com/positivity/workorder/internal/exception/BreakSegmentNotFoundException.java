package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when a break segment is not found or does not belong to the
 * expected work session.
 */
public class BreakSegmentNotFoundException extends RuntimeException {
    public BreakSegmentNotFoundException(UUID breakSegmentId) {
        super("BreakSegment not found: " + breakSegmentId);
    }

    public BreakSegmentNotFoundException(UUID breakSegmentId, UUID workSessionId) {
        super("BreakSegment " + breakSegmentId + " does not belong to WorkSession " + workSessionId);
    }
}
