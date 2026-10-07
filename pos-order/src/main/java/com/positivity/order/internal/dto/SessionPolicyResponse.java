package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The tenant's drawer policy and its history (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6
 * "Drawer limits", AW19). Amounts are in the functional currency.
 */
@Schema(description = "The tenant's drawer policy: allowed and cashier limit per movement type, tolerance and history")
public record SessionPolicyResponse(
        @Schema(description = "The stored policy's version; null while the defaults apply")
        Long version,

        @Schema(description = "One row per movement type; only PETTY_EXPENSE and VENDOR_COD are editable")
        List<TypePolicy> types,

        @Schema(description = "Over/short above which a close needs order:session:approve_variance", example = "5.00")
        BigDecimal overShortTolerance,

        @Schema(description = "Every change, newest first") List<Change> history) {

    @Schema(description = "One movement type's policy")
    public record TypePolicy(
            @Schema(
                    example = "PETTY_EXPENSE",
                    allowableValues = {"PETTY_EXPENSE", "VENDOR_COD", "BANK_DROP", "FLOAT_CHANGE"})
            String type,

            @Schema(description = "Whether cashiers may record it")
            boolean allowed,

            @Schema(
                    description = "Cashier limit on a session's running total; null when there is none",
                    example = "50.00")
            BigDecimal cashierLimit,

            @Schema(description = "Whether every movement of the type needs a manager")
            boolean alwaysNeedsManager,

            @Schema(description = "Whether the policy may change it (false: read-only row)")
            boolean editable) {}

    @Schema(description = "One changed setting")
    public record Change(
            @Schema(example = "OVER_SHORT_TOLERANCE") String setting,
            @Schema(example = "5.00") String oldValue,
            @Schema(example = "3.00") String newValue,
            @Schema(description = "Who changed it") String actor,
            @Schema(description = "Why") String justification,
            @Schema(description = "When") Instant changedAt) {}
}
