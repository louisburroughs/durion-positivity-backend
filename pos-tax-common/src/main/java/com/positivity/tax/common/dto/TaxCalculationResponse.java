package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxJurisdictionType;
import com.positivity.tax.common.enums.TaxReferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from tax calculation.
 * <p>
 * Contains the calculated tax amounts broken down by jurisdiction,
 * along with totals and metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        name = "TaxCalculationResponse",
        description = "Tax calculation response containing totals and jurisdiction/line-level tax breakdown")
public class TaxCalculationResponse {

    /**
     * Total amount before tax (sum of all line item subtotals).
     */
    @NotNull(message = "subtotal is required")
    @PositiveOrZero(message = "subtotal must be >= 0")
    @Schema(description = "Total amount before tax", example = "150.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal subtotal;

    /**
     * Total tax amount across all jurisdictions.
     */
    @NotNull(message = "totalTax is required")
    @PositiveOrZero(message = "totalTax must be >= 0")
    @Schema(description = "Total tax amount", example = "15.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal totalTax;

    /**
     * Total amount including tax (subtotal + totalTax).
     */
    @NotNull(message = "total is required")
    @PositiveOrZero(message = "total must be >= 0")
    @Schema(description = "Total amount including tax", example = "165.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal total;

    /**
     * Combined tax rate as a percentage (totalTax / subtotal * 100).
     */
    @NotNull(message = "effectiveTaxRate is required")
    @PositiveOrZero(message = "effectiveTaxRate must be >= 0")
    @Schema(
            description = "Effective tax rate: total tax divided by the exempt-filtered taxable base (the taxable"
                    + " amount remaining after tax-exempt lines are excluded), expressed as percentage"
                    + " points. This is NOT total tax divided by the raw subtotal (ADR-0042).",
            example = "10.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal effectiveTaxRate;

    /**
     * List of jurisdictions that apply to this calculation.
     */
    @NotNull(message = "jurisdictions is required")
    @Valid
    @Schema(
            description = "Applied tax jurisdictions with per-jurisdiction tax amounts",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<TaxJurisdiction> jurisdictions;

    /**
     * Breakdown of tax per line item.
     * <p>
     * Map key is lineItemId, value is tax amount for that line.
     */
    @NotNull(message = "lineItemTaxes is required")
    @Valid
    @Schema(description = "Per-line-item tax breakdown", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LineItemTax> lineItemTaxes;

    /**
     * Whether this calculation was performed in test mode.
     */
    @Schema(
            description = "Whether calculation was performed in test mode",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean testMode;

    /**
     * Timestamp when the calculation was performed.
     */
    @NotNull(message = "calculatedAt is required")
    @Schema(
            description = "Timestamp when calculation completed",
            example = "2026-02-21T09:18:40Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant calculatedAt;

    /**
     * Reference ID from the request (if provided).
     */
    @Schema(
            description = "Reference ID echoed from request",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID referenceId;

    @Schema(
            description = "Optional source transaction type associated with referenceId",
            example = "ESTIMATE",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private TaxReferenceType referenceType;

    /**
     * Optional external transaction ID if using external tax service.
     */
    @Schema(
            description = "External tax provider transaction identifier",
            example = "tx_9f0d2c",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String externalTransactionId;

    /**
     * Nested class for line item tax breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "LineItemTax", description = "Tax breakdown for a single line item")
    public static class LineItemTax {
        @NotBlank(message = "lineItemId is required")
        @Schema(description = "Line item identifier", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        private String lineItemId;

        @NotNull(message = "subtotal is required")
        @PositiveOrZero(message = "subtotal must be >= 0")
        @Schema(
                description = "Line subtotal before tax",
                example = "100.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal subtotal;

        @NotNull(message = "taxAmount is required")
        @PositiveOrZero(message = "taxAmount must be >= 0")
        @Schema(description = "Line tax amount", example = "10.00", requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal taxAmount;

        @NotNull(message = "total is required")
        @PositiveOrZero(message = "total must be >= 0")
        @Schema(
                description = "Line total including tax",
                example = "110.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal total;

        @Schema(
                description = "Whether this line item is tax exempt",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean taxExempt;

        /**
         * Additive per-jurisdiction tax breakdown for this line item.
         * <p>
         * Never {@code null}; defaults to an empty list. Provides the individual
         * jurisdiction rows (type, code, rate, amount) that sum to
         * {@link #taxAmount}.
         */
        @Valid
        @Builder.Default
        @Schema(
                description = "Per-jurisdiction tax breakdown for this line item; rows sum to taxAmount",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private List<JurisdictionTax> jurisdictions = new ArrayList<>();
    }

    /**
     * Nested class for the per-jurisdiction tax breakdown within a single line item.
     * <p>
     * Compact companion to {@link TaxJurisdiction}: {@code jurisdictionType} mirrors
     * the naming of the top-level jurisdiction rows, while {@code rate}/{@code amount}
     * are the per-line analogues of {@code taxRate}/{@code taxAmount}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(name = "JurisdictionTax", description = "Per-jurisdiction tax breakdown for a single line item")
    public static class JurisdictionTax {

        @NotNull(message = "jurisdictionType is required")
        @Schema(
                description = "Jurisdiction type (e.g. STATE, COUNTY, CITY, DISTRICT)",
                example = "STATE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private TaxJurisdictionType jurisdictionType;

        @NotBlank(message = "code is required")
        @Schema(
                description = "Jurisdiction code; in test mode this is the jurisdiction type code"
                        + " (e.g. STATE, COUNTY, CITY). With an external tax provider it carries the"
                        + " provider's taxing-authority code.",
                example = "STATE",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String code;

        @NotNull(message = "rate is required")
        @PositiveOrZero(message = "rate must be >= 0")
        @Schema(
                description = "Tax rate applied for this jurisdiction, expressed as a decimal fraction"
                        + " (e.g. 0.0725 for 7.25%)",
                example = "0.0725",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal rate;

        @NotNull(message = "amount is required")
        @PositiveOrZero(message = "amount must be >= 0")
        @Schema(
                description = "Tax amount calculated for this jurisdiction on this line item",
                example = "6.53",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private BigDecimal amount;
    }
}
