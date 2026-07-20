package com.positivity.tax.common.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

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
@Schema(description = "Single line item to be evaluated for tax calculation")
public class TaxLineItem {

    /**
     * Unique identifier for the line item (e.g., estimate item ID, invoice line ID).
     */
    @NotBlank(message = "Line item ID is required")
    @Schema(
            description = "Unique identifier for the line item (e.g., estimate item ID, invoice line ID)",
            example = "1",
            requiredMode = REQUIRED)
    private String lineItemId;

    /**
     * Description of the item being taxed.
     */
    @NotBlank(message = "Description is required")
    @Schema(
            description = "Description of the item being taxed",
            example = "Oil Change Service",
            requiredMode = REQUIRED)
    private String description;

    /**
     * Quantity of the item.
     */
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    @Schema(description = "Quantity of the item", example = "1", requiredMode = REQUIRED)
    private BigDecimal quantity;

    /**
     * Unit price of the item before tax.
     */
    @NotNull(message = "Unit price is required")
    @Positive(message = "Unit price must be positive")
    @Schema(description = "Unit price of the item before tax", example = "89.99", requiredMode = REQUIRED)
    private BigDecimal unitPrice;

    /**
     * Total amount before tax (quantity * unitPrice).
     * <p>
     * If not provided, will be calculated as quantity * unitPrice.
     */
    @Schema(
            description = "Total amount before tax (quantity x unitPrice); calculated when omitted",
            example = "89.99",
            requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    /**
     * Optional tax category code (e.g., "GOODS", "SERVICES", "LABOR").
     * <p>
     * Different categories may have different tax rates depending on jurisdiction.
     */
    @Schema(
            description = "Optional tax category code (e.g., GOODS, SERVICES, LABOR)",
            example = "SERVICES",
            requiredMode = NOT_REQUIRED)
    private String taxCategory;

    /**
     * Whether this item is tax-exempt.
     */
    @Builder.Default
    @Schema(description = "Whether this item is tax-exempt", example = "false", requiredMode = NOT_REQUIRED)
    private boolean taxExempt = false;

    /**
     * Optional reason a certificate-backed exemption is claimed for this line (story T3).
     * <p>
     * When present (or when a {@code certificateId} is supplied, or a request-level
     * {@code customerExemption} is set), the exemption is treated as a
     * certificate-backed claim: it is honored only if an active, effective, non-expired
     * exemption certificate is found; otherwise the line is taxed anyway and flagged
     * {@code exemptionDenied} in the response (decision D-T2). A bare {@code taxExempt=true}
     * with no reason/certificate remains an intrinsic non-taxable declaration.
     */
    @Schema(
            description = "Optional certificate-backed exemption reason for this line (RESALE, GOVERNMENT,"
                    + " NONPROFIT, AGRICULTURAL, OTHER)",
            example = "RESALE",
            requiredMode = NOT_REQUIRED)
    private ExemptionReasonCode exemptionReasonCode;

    /**
     * Optional exemption-certificate identifier backing this line's exemption claim (story T3).
     */
    @Schema(
            description = "Optional exemption-certificate identifier backing this line's exemption claim",
            example = "018f0000-0000-7000-8000-0000000000aa",
            requiredMode = NOT_REQUIRED)
    private UUID exemptionCertificateId;

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
