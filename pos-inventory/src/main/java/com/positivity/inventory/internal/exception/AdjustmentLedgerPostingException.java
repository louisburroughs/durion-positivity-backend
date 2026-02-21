package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when a cycle count adjustment cannot be posted to the inventory ledger.
 */
public class AdjustmentLedgerPostingException extends RuntimeException {

    private final UUID adjustmentId;

    public AdjustmentLedgerPostingException(UUID adjustmentId, String message, Throwable cause) {
        super(message, cause);
        this.adjustmentId = adjustmentId;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }
}
