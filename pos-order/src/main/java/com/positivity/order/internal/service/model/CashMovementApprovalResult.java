package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * A minted single-use approval token, when it expires, and the amount it is bound to with its currency
 * (CAP:550 S16, #2512; AW31, ADR-0067 R-1).
 */
public record CashMovementApprovalResult(
        @NonNull String approvalToken,
        @NonNull Instant expiresAt,
        @NonNull BigDecimal amount,
        @NonNull String currencyCode) {

    @Override
    public String toString() {
        return "CashMovementApprovalResult[expiresAt=" + expiresAt + ", amount=" + amount + ", currencyCode="
                + currencyCode + "]";
    }
}
