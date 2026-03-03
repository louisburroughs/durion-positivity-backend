package com.positivity.workorder.internal.exception;

import java.util.UUID;

public class TimeEntryNotFoundException extends RuntimeException {
    public TimeEntryNotFoundException(UUID id) {
        super("TimeEntry not found: " + id);
    }
}
