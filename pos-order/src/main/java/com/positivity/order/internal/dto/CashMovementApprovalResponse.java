package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single-use approval token (CAP:550 S16, #2512; AW31): bound to the session, reason, amount (in its
 * currency, ADR-0067) and category or vendor; send it as the movement's {@code approvalToken} before it
 * expires.
 */
@Schema(description = "A single-use manager approval token for one cash movement")
public record CashMovementApprovalResponse(
        @Schema(description = "The token; shown once, only its hash is stored")
        String approvalToken,

        @Schema(description = "When the token expires", example = "2026-10-07T12:05:00Z")
        Instant expiresAt,

        @Schema(description = "The amount the token is bound to", example = "25.00")
        BigDecimal amount,

        @Schema(description = "ISO 4217 code of the amount (the functional currency)", example = "USD")
        String currencyCode) {

    @Override
    public String toString() {
        return "CashMovementApprovalResponse[expiresAt=" + expiresAt + ", amount=" + amount + ", currencyCode="
                + currencyCode + "]";
    }
}
