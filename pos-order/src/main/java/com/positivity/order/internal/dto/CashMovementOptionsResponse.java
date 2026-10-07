package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What the register may offer the cashier for one session (CAP:550 S16, #2512; spec §6.2).
 */
@Schema(description = "Cash movement reasons, limits, running totals and petty-expense categories for one session")
public record CashMovementOptionsResponse(
        @Schema(description = "The session") UUID sessionId,

        @Schema(
                description = "ISO 4217 code of the limits and running totals (the functional currency)",
                example = "USD")
        String currencyCode,

        @Schema(description = "One entry per fixed reason") List<ReasonOption> reasons,

        @Schema(description = "The ACTIVE petty-expense categories")
        List<CategoryOption> categories) {

    @Schema(description = "One reason's options")
    public record ReasonOption(
            @Schema(
                    example = "PETTY_EXPENSE",
                    allowableValues = {"PETTY_EXPENSE", "VENDOR_COD", "BANK_DROP", "FLOAT_INCREASE", "FLOAT_DECREASE"})
            String reason,

            @Schema(
                    description = "Direction",
                    allowableValues = {"PAID_IN", "PAID_OUT"})
            String direction,

            @Schema(description = "Whether the reason may be recorded now (policy on and session OPEN)")
            boolean allowedNow,

            @Schema(description = "Cashier limit on the session's running total, or null when there is none")
            BigDecimal cashierLimit,

            @Schema(description = "The session's running total of this reason", example = "55.00")
            BigDecimal runningTotal,

            @Schema(description = "Whether every movement of this reason needs a manager (float changes)")
            boolean alwaysNeedsManager,

            @Schema(description = "The fields the reason requires", example = "[\"bagNumber\"]")
            List<String> requiredFields) {}

    @Schema(description = "One ACTIVE petty-expense category")
    public record CategoryOption(
            @Schema(example = "SHOP_SUPPLIES") String code,
            @Schema(example = "Shop supplies") String label,

            @Schema(description = "What belongs in the category")
            String examples) {}
}
