package com.positivity.inventory.internal.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Raised when a cycle-count adjustment approval detects interfering stock
 * movements in the count window (odoo-parity I2, issue #1026): the task's
 * expected-quantity snapshot is stale, the task has been flagged
 * {@code CONFLICT}, and the approval is rejected until the reviewer makes an
 * explicit choice — request a recount, or re-approve the adjustment (approving
 * a {@code CONFLICT} task recomputes the variance against current on-hand).
 */
public class CycleCountConflictException extends RuntimeException {

    public CycleCountConflictException(UUID taskId, BigDecimal movementDelta) {
        super("Cycle count task " + taskId + " has interfering stock movements (net on-hand delta "
                + plain(movementDelta)
                + ") since the count snapshot; task flagged CONFLICT. Request a recount, or approve again to accept"
                + " the variance recomputed against current on-hand.");
    }

    // Building the error message must never itself throw and mask the conflict being reported.
    private static String plain(BigDecimal value) {
        return value == null ? "unknown" : value.toPlainString();
    }
}
