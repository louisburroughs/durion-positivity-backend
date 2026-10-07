package com.positivity.order.internal.service.model;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/** A minted single-use approval token and when it expires (CAP:550 S16, #2512; AW31). */
public record CashMovementApprovalResult(
        @NonNull String approvalToken, @NonNull Instant expiresAt) {

    @Override
    public String toString() {
        return "CashMovementApprovalResult[expiresAt=" + expiresAt + "]";
    }
}
