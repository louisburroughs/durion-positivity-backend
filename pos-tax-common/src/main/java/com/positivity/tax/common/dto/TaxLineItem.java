package com.positivity.tax.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;

/**
 * Represents a single line item for tax calculation.
 * <p>
 * Contains the necessary information to calculate tax for an individual item,
 * including quantity, unit price, and optional tax category.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaxLineItem {

    /**
     * Unique identifier for the line item (e.g., estimate item ID, invoice line ID).
     */
    @NotBlank(message = "Line item ID is required")
    private String lineItemId;

    /**
     * Description of the item being taxed.
     */
    @NotBlank(message = "Description is required")
    private String description;

    /**
     * Quantity of the item.
     */
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private BigDecimal quantity;

    /**
     * Unit price of the item before tax.
     */
    @NotNull(message = "Unit price is required")
    @Positive(message = "Unit price must be positive")
    private BigDecimal unitPrice;

    /**
     * Total amount before tax (quantity * unitPrice).
     * <p>
     * If not provided, will be calculated as quantity * unitPrice.
     */
    private BigDecimal subtotal;

    /**
     * Optional tax category code (e.g., "GOODS", "SERVICES", "LABOR").
     * <p>
     * Different categories may have different tax rates depending on jurisdiction.
     */
    private String taxCategory;

    /**
     * Whether this item is tax-exempt.
     */
    @Builder.Default
    private boolean taxExempt = false;

    /**
     * Gets the subtotal, calculating it if not explicitly provided.
     *
     * @return the subtotal amount
     */
    @NonNull
    public BigDecimal getSubtotal() {
        if (subtotal == null && quantity != null && unitPrice != null) {
            return quantity.multiply(unitPrice);
        }
        return subtotal != null ? subtotal : BigDecimal.ZERO;
    }
}
