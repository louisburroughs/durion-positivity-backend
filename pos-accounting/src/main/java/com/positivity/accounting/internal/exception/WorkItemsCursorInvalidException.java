package com.positivity.accounting.internal.exception;

/**
 * A work-items cursor that is malformed, was altered, or was issued for another area or another {@code since}
 * (CAP:550 S19, #2515; ruling 8 of 2026-10-17): 400 {@code WORK_ITEMS_CURSOR_INVALID}. The client restarts from the
 * first page.
 */
public class WorkItemsCursorInvalidException extends RuntimeException {

    /** The error code of the response. */
    public static final String CODE = "WORK_ITEMS_CURSOR_INVALID";

    public WorkItemsCursorInvalidException(String message) {
        super(message);
    }
}
