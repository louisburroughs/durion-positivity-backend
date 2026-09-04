package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * No workorder service line exists for the requested id, or it exists but is not part of the
 * named workorder — a workorder-scoped lookup finds nothing either way, so both conditions are
 * the same {@code 404} (issue #1694; matches {@code WorkorderController}'s documented "returns
 * 404 when the line is missing or belongs to another workorder").
 */
public class ServiceLineNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "SERVICE_LINE_NOT_FOUND";

    private ServiceLineNotFoundException(String message) {
        super(message);
    }

    public static ServiceLineNotFoundException forId(UUID serviceLineId) {
        return new ServiceLineNotFoundException("Service line not found: " + serviceLineId);
    }

    public static ServiceLineNotFoundException notOnWorkorder(UUID serviceLineId, UUID workorderId) {
        return new ServiceLineNotFoundException(
                "Service line " + serviceLineId + " does not belong to workorder " + workorderId);
    }
}
