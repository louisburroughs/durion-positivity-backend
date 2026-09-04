package com.positivity.workorder.internal.exception;

import java.util.UUID;

/** No approval configuration exists for the requested id (issue #1694). */
public class ApprovalConfigurationNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "APPROVAL_CONFIGURATION_NOT_FOUND";

    public ApprovalConfigurationNotFoundException(UUID configId) {
        super("Configuration not found: " + configId);
    }
}
