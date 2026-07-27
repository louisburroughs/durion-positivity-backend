package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One requested SKU line of a transfer-order creation request (odoo-parity C1, issue #1035).
 */
@Schema(description = "Requested SKU line of a transfer order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferOrderLineRequest {

    @Schema(description = "SKU / stock item identifier to transfer", example = "OIL-5W30-5QT", requiredMode = REQUIRED)
    @NotBlank(message = "SKU is required")
    private String sku;

    @Schema(description = "Quantity requested for transfer; must be positive", example = "6", requiredMode = REQUIRED)
    @NotNull(message = "Requested quantity is required")
    @Positive(message = "Requested quantity must be positive")
    private Integer requestedQty;
}
