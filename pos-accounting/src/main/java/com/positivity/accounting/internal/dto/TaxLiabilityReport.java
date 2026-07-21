package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;

/**
 * Sales-Tax Liability report response (story T8, issue #966).
 *
 * <p>Reconciliation-grade report (R-T4 decision A): AvaTax remains the filing
 * system of record; this report confirms that platform-collected tax equals
 * GL-posted tax. Accrual basis — invoice tax is bucketed by the invoice's
 * finalization (posting) date and credit-memo reversals by the credit's posting
 * date, both within the requested date range.
 *
 * <p>Rows are grouped by {@code (jurisdictionType, jurisdictionCode)} at all
 * available geographic levels, ordered state → county → city → special then by
 * code. Exempt/zero-rate sales appear per jurisdiction (exempt base + reasons).
 * Credits net within the jurisdiction+period bucket while gross collected stays
 * visible. A GL-drift block reconciles total net tax against the Sales-Tax Payable
 * (2200) account activity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Sales-Tax Liability report: per-jurisdiction taxable/exempt base, gross, credits, net tax, and GL drift")
public class TaxLiabilityReport {

    @Schema(description = "Period start date (inclusive)", example = "2026-06-01", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @Schema(description = "Period end date (inclusive)", example = "2026-06-30", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-30T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    @Schema(
            description =
                    "Per-jurisdiction rows ordered by level (state/county/city/special) then code; empty when no in-period tax exists",
            requiredMode = REQUIRED)
    @NonNull
    private List<TaxLiabilityRow> rows;

    @Schema(
            description = "Grand total taxable base across all jurisdictions",
            example = "12500.0000",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalTaxableBase;

    @Schema(
            description = "Grand total exempt base across all jurisdictions",
            example = "1500.0000",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalExemptBase;

    @Schema(
            description = "Grand total gross tax collected across all jurisdictions",
            example = "1031.25",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalTaxCollectedGross;

    @Schema(
            description = "Grand total credits netted across all jurisdictions",
            example = "82.50",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalCreditsNetted;

    @Schema(description = "Grand total net tax across all jurisdictions", example = "948.75", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalNetTax;

    @Schema(
            description = "GL-drift reconciliation against the Sales-Tax Payable (2200) account",
            requiredMode = REQUIRED)
    @NonNull
    private TaxLiabilityReconciliation reconciliation;
}
