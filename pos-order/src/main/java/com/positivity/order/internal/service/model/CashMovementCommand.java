package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Record a drawer cash movement (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6, AW15). The
 * cashier is never part of the command: it comes from the security context (ADR-0018). The fields
 * are validated by the service, so a malformed request answers {@code REGISTER_SESSION_INVALID_ARGUMENT}.
 *
 * @param sessionId the OPEN session
 * @param requestId the register's idempotency key (UUIDv7)
 * @param reason one of the fixed reasons, as sent
 * @param amount the positive amount
 * @param currencyCode the amount's ISO 4217 code; must be the functional currency
 * @param categoryCode the petty-expense category, for {@code PETTY_EXPENSE}
 * @param vendorId the vendor, for {@code VENDOR_COD}
 * @param bagNumber the deposit bag, for {@code BANK_DROP}
 * @param receiptReference the receipt, for {@code PETTY_EXPENSE}
 * @param note optional free text; required for {@code PETTY_EXPENSE}
 * @param approvalToken a manager's single-use approval token from the step-up, when one is needed
 */
public record CashMovementCommand(
        @NonNull UUID sessionId,
        @Nullable UUID requestId,
        @Nullable String reason,
        @Nullable BigDecimal amount,
        @Nullable String currencyCode,
        @Nullable String categoryCode,
        @Nullable UUID vendorId,
        @Nullable String bagNumber,
        @Nullable String receiptReference,
        @Nullable String note,
        @Nullable String approvalToken) {

    public CashMovementCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    /** Never prints the approval token. */
    @Override
    public String toString() {
        return "CashMovementCommand[sessionId=" + sessionId + ", requestId=" + requestId + ", reason=" + reason
                + ", amount=" + amount + ", currencyCode=" + currencyCode + ", categoryCode=" + categoryCode
                + ", vendorId=" + vendorId + ", bagNumber="
                + bagNumber + ", receiptReference=" + receiptReference + ", approvalToken="
                + (approvalToken == null ? "absent" : "present") + "]";
    }
}
