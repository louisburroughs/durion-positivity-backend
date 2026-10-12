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

        @Schema(
                description = "ISO 4217 code of every amount on the drawer, stamped from the functional"
                        + " currency when it opened (ADR-0067); a configuration change applies only to"
                        + " drawers opened later",
                example = "USD")
        String currencyCode,

        String closedByClerkId,
        Instant openedAt,
        Instant closingStartedAt,
        Instant closedAt,

        @Schema(
                description = "The opener's display name, \"First Last\", name only; null when it can't be resolved."
                        + " Never the username (#2608, ADR-0064)",
                nullable = true,
                example = "Dana Reyes")
        String openedByName,

        @Schema(
                description = "The closer's display name, \"First Last\", name only; null until the session is closed"
                        + " or when it can't be resolved. Never the username (#2608, ADR-0064)",
                nullable = true,
                example = "Sam Ortiz")
        String closedByName) {

    /** Never prints the actors' display names (ADR-0072). */
    @Override
    public String toString() {
        return "RegisterSessionResponse[sessionId=" + sessionId + ", terminalId=" + terminalId + ", status=" + status
                + ", currencyCode=" + currencyCode + "]";
    }

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
                s.currencyCode(),
                s.closedByClerkId(),
                s.openedAt(),
                s.closingStartedAt(),
                s.closedAt(),
                s.openedByName(),
                s.closedByName());
    }
}
