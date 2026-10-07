package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A single-use approval token (CAP:550 S16, #2512; AW31): bound to the session, reason, amount and
 * category or vendor; send it as the movement's {@code approvalToken} before it expires.
 */
@Schema(description = "A single-use manager approval token for one cash movement")
public record CashMovementApprovalResponse(
        @Schema(description = "The token; shown once, only its hash is stored")
        String approvalToken,

        @Schema(description = "When the token expires", example = "2026-10-07T12:05:00Z")
        Instant expiresAt) {

    @Override
    public String toString() {
        return "CashMovementApprovalResponse[expiresAt=" + expiresAt + "]";
    }
}
