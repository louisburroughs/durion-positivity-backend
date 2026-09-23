package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when a cycle count adjustment cannot be posted to the inventory ledger.
 *
 * <p>The message names the adjustment, as {@link ScrapLedgerPostingException} does, because the
 * adjustment is left {@code FAILED} under that id (#2170): on the below-threshold create path the
 * 500 is the only place the caller learns it.
 */
public class AdjustmentLedgerPostingException extends RuntimeException {

    private final UUID adjustmentId;

    public AdjustmentLedgerPostingException(UUID adjustmentId, String message, Throwable cause) {
        super("Adjustment " + adjustmentId + ": " + message, cause);
        this.adjustmentId = adjustmentId;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }
}
