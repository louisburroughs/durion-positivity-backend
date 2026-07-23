package com.positivity.inventory.internal.dto.transfer;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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

    @Schema(
            description = "Lot number the dispatched units are drawn from (dispatch only; receive and short-close"
                    + " reuse the lot recorded at dispatch). Required (422 LOT_NUMBER_REQUIRED) when the SKU is"
                    + " LOT-tracked; must reference an existing (422 LOT_UNKNOWN) ACTIVE (422 LOT_NOT_AVAILABLE)"
                    + " lot. Ignored for untracked SKUs",
            example = "LOT-2026-A",
            requiredMode = NOT_REQUIRED)
    @Size(max = 128)
    private String lotNumber;

    /** Pre-E2 arity kept for existing callers/tests: no lot number keyed. */
    public TransferQuantityLineRequest(UUID lineId, Integer quantity) {
        this(lineId, quantity, null);
    }
}
