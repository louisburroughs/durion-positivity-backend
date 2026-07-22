package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * GL-drift / reconciliation block of the Sales-Tax Liability report (story T8,
 * issue #966).
 *
 * <p>Under D-4 (single Sales-Tax-Payable GL account, report-time aggregation) the
 * report's total net tax is reconciled against the credit-normal period activity of
 * the Sales-Tax Payable account (code {@code 2200}). Invoice finalization posts
 * {@code Cr 2200}; a POSTED credit memo posts {@code Dr 2200}. On a clean ledger the
 * platform-calculated net tax equals the 2200 net activity and {@link #drift} is zero;
 * a non-zero drift flags a mismatch between calculated and posted tax.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Reconciliation of report net tax against the Sales-Tax Payable (2200) GL account period activity")
public class TaxLiabilityReconciliation {

    @Schema(
            description = "Sales-Tax Payable GL account code being reconciled against",
            example = "2200",
            requiredMode = REQUIRED)
    @NonNull
    private String taxPayableAccountCode;

    @Schema(
            description =
                    "Credit-normal net activity of account 2200 in the period (Σ credit - Σ debit of POSTED lines); zero when the account has no activity or is not seeded",
            example = "948.75",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal glNetActivity;

    @Schema(
            description = "Report's total net tax across all jurisdictions (echoes report totals.netTax)",
            example = "948.75",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal reportNetTax;

    @Schema(
            description =
                    "Credit-memo tax that could not be attributed to any jurisdiction (the credit carries no frozen breakdown and its original invoice has no attributable tax rows). Excluded from the jurisdiction rows by design, so it accounts for exactly this much of the drift",
            example = "0.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal unattributedCredits;

    @Schema(
            description = "GL drift: reportNetTax - glNetActivity. Zero on a clean ledger; non-zero flags a mismatch",
            example = "0.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal drift;

    @Schema(
            description = "True when |drift| is within the 0.01 reconciliation tolerance",
            example = "true",
            requiredMode = REQUIRED)
    @NonNull
    private Boolean reconciled;
}
