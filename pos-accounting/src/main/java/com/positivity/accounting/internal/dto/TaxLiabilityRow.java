package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One jurisdiction row of the Sales-Tax Liability report (story T8, issue #966).
 *
 * <p>Rows are grouped by {@code (jurisdictionType, jurisdictionCode)} at all
 * available geographic levels (state / county / city / special). Each row shows
 * gross tax collected, credits netted (pro-rata attribution of POSTED credit-memo
 * reversals), and net tax, alongside the taxable and exempt bases.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Per-jurisdiction sales-tax liability row (taxable/exempt base, gross, credits netted, net tax)")
public class TaxLiabilityRow {

    @Schema(
            description = "Jurisdiction level: STATE, COUNTY, CITY, or SPECIAL",
            example = "STATE",
            requiredMode = REQUIRED)
    @NonNull
    private String jurisdictionType;

    @Schema(description = "Jurisdiction code as reported by the tax engine", example = "WA", requiredMode = REQUIRED)
    @NonNull
    private String jurisdictionCode;

    @Schema(
            description =
                    "Human-readable jurisdiction name when available; null when the replica carries only the code",
            example = "Washington",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String jurisdictionName;

    @Schema(
            description = "Sum of taxable base for non-exempt lines in this jurisdiction",
            example = "12500.0000",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal taxableBase;

    @Schema(
            description = "Sum of taxable base for exempt / zero-rate lines in this jurisdiction",
            example = "1500.0000",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal exemptBase;

    @Schema(
            description =
                    "Distinct exemption reason codes contributing to the exempt base (ascending); empty when none",
            requiredMode = REQUIRED)
    @NonNull
    private List<String> exemptionReasons;

    @Schema(
            description = "Gross tax collected (Σ tax_amount of non-exempt lines) before credits",
            example = "1031.25",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal taxCollectedGross;

    @Schema(
            description =
                    "Credit/refund tax netted against this jurisdiction — POSTED credit-memo reversals allocated pro-rata to collected tax",
            example = "82.50",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal creditsNetted;

    @Schema(
            description = "Net tax owed for this jurisdiction: taxCollectedGross - creditsNetted",
            example = "948.75",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal netTax;
}
