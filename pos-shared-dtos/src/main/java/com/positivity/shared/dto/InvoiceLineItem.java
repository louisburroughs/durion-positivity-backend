package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Line item DTO used for invoice generation payloads and responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Invoice line item details.")
public class InvoiceLineItem {

    @NonNull
    @NotNull
    @Schema(
            description = "Line item description.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Brake pad replacement")
    private String description;

    @NonNull
    @NotNull
    @Schema(description = "Quantity billed.", requiredMode = Schema.RequiredMode.REQUIRED, example = "2.0")
    private BigDecimal quantity;

    @NonNull
    @NotNull
    @Schema(description = "Unit price amount.", requiredMode = Schema.RequiredMode.REQUIRED, example = "45.00")
    private BigDecimal unitPrice;

    @NonNull
    @NotNull
    @Schema(
            description = "Line amount (quantity x unit price).",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "90.00")
    private BigDecimal amount;

    @Nullable
    @Schema(
            description = "Line item type (e.g. PART, LABOR, FEE, TAX).",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "LABOR")
    private String type;
}
