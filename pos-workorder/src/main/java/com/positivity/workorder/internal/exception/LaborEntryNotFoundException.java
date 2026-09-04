package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * No workorder labor entry exists for the requested id (issue #1728: {@code adjustLaborHours}
 * used to answer this with a bodiless {@code 404} built directly in the controller, dropping the
 * {@code ApiError} envelope, correlation id, and {@code X-Correlation-Id} header the rest of this
 * module's not-found responses carry).
 */
public class LaborEntryNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "LABOR_ENTRY_NOT_FOUND";

    public LaborEntryNotFoundException(UUID laborEntryId) {
        super("Labor entry not found: " + laborEntryId);
    }
}
