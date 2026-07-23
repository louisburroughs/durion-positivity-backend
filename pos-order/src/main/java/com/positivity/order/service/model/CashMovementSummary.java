package com.positivity.order.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read model of a drawer cash movement (parity story G1).
 *
 * @param movementId movement identifier
 * @param sessionId session the movement belongs to
 * @param movementType PAID_IN or PAID_OUT
 * @param amount positive cash amount moved
 * @param reason free-text reason
 * @param clerkId clerk who recorded it
 * @param occurredAt when the movement was recorded
 */
public record CashMovementSummary(
        @NonNull UUID movementId,
        @NonNull UUID sessionId,
        @NonNull String movementType,
        @NonNull BigDecimal amount,
        @NonNull String reason,
        @NonNull String clerkId,
        @NonNull Instant occurredAt) {}
