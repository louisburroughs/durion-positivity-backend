package com.positivity.inventory.internal.exception;

import java.util.UUID;

/**
 * Exception thrown when there is insufficient Available-to-Promise (ATP)
 * inventory
 * to satisfy a HARD allocation promotion request.
 *
 * <p>
 * ATP = on-hand − HARD-allocated. SOFT allocations do NOT reduce ATP per
 * ADR-0001.
 *
 * <p>
 * Thrown by {@code ReservationService#promoteToHard} when on-hand inventory
 * cannot cover the requested HARD allocation quantity. The reservation status
 * transitions to {@code BACKORDERED}.
 *
 * Issue: #29
 */
public class InsufficientAtpException extends RuntimeException {

    private final String errorCode = "INSUFFICIENT_ATP";
    private final UUID allocationId;
    private final int requiredQuantity;
    private final int availableAtp;

    public InsufficientAtpException(UUID allocationId, int requiredQuantity, int availableAtp) {
        super(String.format(
                "INSUFFICIENT_ATP: allocationId=%s requiredQuantity=%d availableAtp=%d",
                allocationId, requiredQuantity, availableAtp));
        this.allocationId = allocationId;
        this.requiredQuantity = requiredQuantity;
        this.availableAtp = availableAtp;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public UUID getAllocationId() {
        return allocationId;
    }

    public int getRequiredQuantity() {
        return requiredQuantity;
    }

    public int getAvailableAtp() {
        return availableAtp;
    }
}
