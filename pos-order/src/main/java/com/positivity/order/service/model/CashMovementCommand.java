package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Command to record a non-sale drawer cash movement (parity story G1, spec R6.6).
 *
 * @param sessionId open session the movement is recorded against
 * @param movementType PAID_IN or PAID_OUT
 * @param amount positive cash amount moved
 * @param reason free-text reason (e.g. "petty cash to office", "bank change order")
 * @param clerkId clerk recording the movement
 */
public record CashMovementCommand(
        @NonNull UUID sessionId,
        @NonNull String movementType,
        @NonNull BigDecimal amount,
        @NonNull String reason,
        @NonNull String clerkId) {

    public CashMovementCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(movementType, "movementType must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(clerkId, "clerkId must not be null");
    }
}
