package com.positivity.order.internal.dto;

import com.positivity.order.service.model.CashMovementSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A drawer cash movement")
public record CashMovementResponse(
        UUID movementId,
        UUID sessionId,
        String movementType,
        BigDecimal amount,
        String reason,
        String clerkId,
        Instant occurredAt) {

    public static CashMovementResponse from(CashMovementSummary m) {
        return new CashMovementResponse(
                m.movementId(), m.sessionId(), m.movementType(), m.amount(), m.reason(), m.clerkId(), m.occurredAt());
    }
}
