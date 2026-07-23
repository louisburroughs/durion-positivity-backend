package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One explicit line quantity of a transfer dispatch/receive request (odoo-parity C2, issue
 * #1036). Lines omitted from the request default to their full remaining quantity.
 */
@Schema(description = "Explicit per-line quantity for a transfer dispatch or receive")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferQuantityLineRequest {

    @Schema(description = "Transfer order line identifier", requiredMode = REQUIRED)
    @NotNull(message = "Line ID is required")
    private UUID lineId;

    @Schema(description = "Quantity to dispatch/receive on this line; must be positive", requiredMode = REQUIRED)
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    private Integer quantity;
}
