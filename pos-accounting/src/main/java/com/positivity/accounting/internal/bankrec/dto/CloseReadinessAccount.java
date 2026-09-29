package com.positivity.accounting.internal.bankrec.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Close readiness of one in-scope account (SPEC-manual-bank-reconciliation §5.3; story S6, #2305).
 *
 * @param baselineDate the baseline that applies at the period end — the one the {@code UNEXPLAINED_*} checks
 *     used; null when the account has none (the current baseline is {@code reconciliationBaselineDate} on the
 *     bank-accounts read)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Close readiness of one in-scope account: frontiers, OPEN outstanding items and checks")
public record CloseReadinessAccount(
        @Schema(description = "GL account id") UUID glAccountId,

        @Schema(description = "GL account code", example = "1000")
        String accountCode,

        @Schema(description = "GL account name", example = "Cash")
        String accountName,

        @Schema(
                description = "Baseline that applies at the period end; the lower bound of the UNEXPLAINED_*"
                        + " checks; null when the account has no acknowledged statement",
                example = "2026-06-01",
                nullable = true)
        LocalDate baselineDate,

        @Schema(description = "End date of the latest COMMITTED statement", example = "2026-08-31", nullable = true)
        LocalDate coverageFrontier,

        @Schema(
                description =
                        "End of the contiguous FINALIZED chain from the baseline that applies at the" + " period end",
                example = "2026-08-31",
                nullable = true)
        LocalDate reconciledFrontier,

        @Schema(description = "OPEN outstanding items dated on or before the period end (the explained differences)")
        List<CloseReadinessOutstandingItem> openOutstandingItems,

        @Schema(description = "Sum of the OPEN outstanding items' signed amounts", example = "-120.00")
        BigDecimal openOutstandingItemSum,

        @Schema(description = "The account's checks that fired")
        List<CloseReadinessCheck> checks) {}
