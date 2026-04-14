package com.positivity.tax.common.dto;

import com.positivity.tax.common.enums.TaxReferenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
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
            description = "Effective tax rate as percentage",
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
    @Schema(description = "Whether calculation was performed in test mode", example = "false")
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
    @Schema(description = "Reference ID echoed from request", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID referenceId;

    @Schema(description = "Optional source transaction type associated with referenceId", example = "ESTIMATE")
    private TaxReferenceType referenceType;

    /**
     * Optional external transaction ID if using external tax service.
     */
    @Schema(description = "External tax provider transaction identifier", example = "tx_9f0d2c")
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
        @Schema(description = "Line item identifier", example = "\"1\"", requiredMode = Schema.RequiredMode.REQUIRED)
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

        @Schema(description = "Whether this line item is tax exempt", example = "false")
        private boolean taxExempt;
    }
}
