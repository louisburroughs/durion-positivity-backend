package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Ask a manager's approval of one cash movement at the drawer (CAP:550 S16, #2512; AW31): the manager's
 * own credentials, checked once by pos-security-service, and what the approval is bound to. The
 * record's string form never prints the password.
 */
public record CashMovementApprovalCommand(
        @NonNull UUID sessionId,
        @Nullable String managerUsername,
        @Nullable String managerPassword,
        @Nullable String reason,
        @Nullable BigDecimal amount,
        @Nullable String categoryCode,
        @Nullable UUID vendorId) {

    public CashMovementApprovalCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    @Override
    public String toString() {
        return "CashMovementApprovalCommand[sessionId=" + sessionId + ", managerUsername=" + managerUsername
                + ", reason=" + reason + ", amount=" + amount + ", categoryCode=" + categoryCode + ", vendorId="
                + vendorId + "]";
    }
}
