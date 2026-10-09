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
 * the tax-payable accounts its mapping keys reach in the period ({@code SALES_TAX_PAYABLE}, 2200 in the template, and
 * each {@code SALES_TAX_PAYABLE_<taxType>} a currency template maps; CAP:550 S32d). Invoice finalization credits them;
 * a POSTED credit memo debits them. On a clean ledger the
 * platform-calculated net tax equals their net activity and {@link #drift} is zero;
 * a non-zero drift flags a mismatch between calculated and posted tax.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Reconciliation of report net tax against the period activity of the tax-payable GL accounts its"
                + " mapping keys reach")
public class TaxLiabilityReconciliation {

    @Schema(
            description = "The tax-payable GL account codes reconciled against, comma-separated in code order: every"
                    + " account a tax-payable mapping key (SALES_TAX_PAYABLE and each SALES_TAX_PAYABLE_<taxType>)"
                    + " maps to in the period; empty when none is mapped",
            example = "2200",
            requiredMode = REQUIRED)
    @NonNull
    private String taxPayableAccountCode;

    @Schema(
            description =
                    "Credit-normal net activity of the tax-payable accounts in the period (Σ credit - Σ debit of POSTED lines); zero when the account has no activity or is not seeded",
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
            description =
                    "True when the UNEXPLAINED drift (drift - unattributedCredits) is within the 0.01 reconciliation tolerance. Unattributed credits inflate drift by exactly their own value, so that portion is explained and does not flag the ledger as out of balance",
            example = "true",
            requiredMode = REQUIRED)
    @NonNull
    private Boolean reconciled;
}
