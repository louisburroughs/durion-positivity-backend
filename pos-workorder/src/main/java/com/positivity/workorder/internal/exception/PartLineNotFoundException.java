package com.positivity.workorder.internal.exception;

import java.util.UUID;

/**
 * No workorder part line exists for the requested id, or it exists but is not part of the named
 * workorder — a workorder-scoped lookup finds nothing either way, so both conditions are the
 * same {@code 404} (issue #1694; matches {@code WorkorderController}'s documented "returns 404
 * when the part is missing or belongs to another workorder").
 */
public class PartLineNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "PART_NOT_FOUND";

    private PartLineNotFoundException(String message) {
        super(message);
    }

    public static PartLineNotFoundException forId(UUID partLineId) {
        return new PartLineNotFoundException("Part not found: " + partLineId);
    }

    public static PartLineNotFoundException notOnWorkorder(UUID partLineId, UUID workorderId) {
        return new PartLineNotFoundException("Part " + partLineId + " does not belong to workorder " + workorderId);
    }
}
