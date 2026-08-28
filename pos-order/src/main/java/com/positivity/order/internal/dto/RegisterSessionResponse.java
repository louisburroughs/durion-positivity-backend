package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Register session state")
public record RegisterSessionResponse(
        UUID sessionId,
        String terminalId,
        UUID locationId,
        String openedByClerkId,
        String status,
        BigDecimal openingFloat,
        BigDecimal countedCash,
        BigDecimal theoreticalCash,
        BigDecimal overShort,
        boolean varianceApproved,
        String closedByClerkId,
        Instant openedAt,
        Instant closingStartedAt,
        Instant closedAt) {

    public static RegisterSessionResponse from(RegisterSessionSummary s) {
        return new RegisterSessionResponse(
                s.sessionId(),
                s.terminalId(),
                s.locationId(),
                s.openedByClerkId(),
                s.status(),
                s.openingFloat(),
                s.countedCash(),
                s.theoreticalCash(),
                s.overShort(),
                s.varianceApproved(),
                s.closedByClerkId(),
                s.openedAt(),
                s.closingStartedAt(),
                s.closedAt());
    }
}
