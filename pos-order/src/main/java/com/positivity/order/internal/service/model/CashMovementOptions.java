package com.positivity.order.internal.service.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What the register may offer the cashier for one session (CAP:550 S16, #2512; spec §6.2): per reason
 * whether it is allowed now, its cashier limit, the session's running total, whether a manager is always
 * needed and the fields it requires; and the ACTIVE petty-expense categories. The vendors join once
 * pos-order holds its vendor copy (S24).
 */
public record CashMovementOptions(
        @NonNull UUID sessionId,
        @NonNull String currencyCode,
        @NonNull List<ReasonOption> reasons,
        @NonNull List<CategoryOption> categories,
        @Nullable EvidenceRule evidenceRule) {

    /** One reason's options. */
    public record ReasonOption(
            @NonNull String reason,
            @NonNull String direction,
            boolean allowedNow,
            @Nullable BigDecimal cashierLimit,
            @NonNull BigDecimal runningTotal,
            boolean alwaysNeedsManager,
            @NonNull List<String> requiredFields) {}

    /** One ACTIVE petty-expense category. */
    public record CategoryOption(
            @NonNull String code,
            @NonNull String label,
            @Nullable String examples,
            @NonNull List<String> offeredRegimes) {}

    /**
     * The drawer receipt's evidence threshold (CAP:550 S32d): from this receipt total the supplier's number is asked
     * for; null when pos-tax did not answer or names none.
     *
     * @param threshold    the receipt total, tax included
     * @param currencyCode its ISO 4217 code
     */
    public record EvidenceRule(
            @NonNull BigDecimal threshold, @NonNull String currencyCode) {}
}
