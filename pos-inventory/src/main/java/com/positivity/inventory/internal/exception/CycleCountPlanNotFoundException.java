package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when a cycle count plan is not found.
 */
public class CycleCountPlanNotFoundException extends RuntimeException {

    public CycleCountPlanNotFoundException(UUID planId) {
        super(String.format("Cycle count plan not found: %s", planId));
    }
}
