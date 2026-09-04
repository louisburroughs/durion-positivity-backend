package com.positivity.workorder.internal.exception;

import java.util.UUID;

/** No change request exists for the requested id (issue #1694). */
public class ChangeRequestNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "CHANGE_REQUEST_NOT_FOUND";

    public ChangeRequestNotFoundException(UUID changeRequestId) {
        super("Change request not found: " + changeRequestId);
    }
}
