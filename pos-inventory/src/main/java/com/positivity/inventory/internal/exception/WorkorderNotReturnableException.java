package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Thrown by {@code submitToStock} when the named work order's status is not one of the two the
 * contract allows a return against — {@code COMPLETED}/{@code CLOSED} (CAP-218 Story #177): parts
 * are returned to stock once the job is done, not mid-repair. Maps to 422 {@code
 * WORKORDER_NOT_RETURNABLE}.
 */
public class WorkorderNotReturnableException extends RuntimeException {

    public WorkorderNotReturnableException(UUID workorderId, String status) {
        super("Workorder " + workorderId + " is not returnable in status " + status
                + " (only COMPLETED or CLOSED workorders accept returns)");
    }
}
