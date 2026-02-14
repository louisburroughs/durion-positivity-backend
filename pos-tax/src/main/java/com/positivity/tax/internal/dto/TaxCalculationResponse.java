package com.positivity.tax.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
public class TaxCalculationResponse {

    /**
     * Total amount before tax (sum of all line item subtotals).
     */
    private BigDecimal subtotal;

    /**
     * Total tax amount across all jurisdictions.
     */
    private BigDecimal totalTax;

    /**
     * Total amount including tax (subtotal + totalTax).
     */
    private BigDecimal total;

    /**
     * Combined tax rate as a percentage (totalTax / subtotal * 100).
     */
    private BigDecimal effectiveTaxRate;

    /**
     * List of jurisdictions that apply to this calculation.
     */
    private List<TaxJurisdiction> jurisdictions;

    /**
     * Breakdown of tax per line item.
     * <p>
     * Map key is lineItemId, value is tax amount for that line.
     */
    private List<LineItemTax> lineItemTaxes;

    /**
     * Whether this calculation was performed in test mode.
     */
    private boolean testMode;

    /**
     * Timestamp when the calculation was performed.
     */
    @Builder.Default
    private Instant calculatedAt = Instant.now();

    /**
     * Reference ID from the request (if provided).
     */
    private String referenceId;

    /**
     * Optional external transaction ID if using external tax service.
     */
    private String externalTransactionId;

    /**
     * Nested class for line item tax breakdown.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineItemTax {
        private String lineItemId;
        private BigDecimal subtotal;
        private BigDecimal taxAmount;
        private BigDecimal total;
        private boolean taxExempt;
    }
}
