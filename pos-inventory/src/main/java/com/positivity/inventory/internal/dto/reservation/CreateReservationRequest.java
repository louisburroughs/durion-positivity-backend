package com.positivity.inventory.internal.dto.reservation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Request to create an inventory reservation against a demand line (a workorder line or a"
                + " sales-order line, CAP #1315) for a stock item. Exactly one of workorderLineId/salesOrderLineId"
                + " must be set; the service rejects a request that sets both or neither.")
public class CreateReservationRequest {

    @Schema(
            description = "Identifier of the workorder line the reservation fulfils, when demand is from a"
                    + " workorder. Exactly one of this and salesOrderLineId must be set.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID workorderLineId;

    @Schema(
            description = "Identifier of the sales-order line the reservation fulfils, when demand is from a sales"
                    + " order (CAP #1315). Exactly one of this and workorderLineId must be set.",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID salesOrderLineId;

    @Schema(
            description = "Identifier of the stock item being reserved",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID stockItemId;

    @Schema(
            description = "Quantity of the stock item required by the reservation",
            example = "4",
            requiredMode = REQUIRED)
    @Positive
    private int requiredQuantity;

    /**
     * Workorder-line convenience constructor, predating the CAP #1315 sales-order-line addition.
     * Equivalent to the all-args constructor with {@code salesOrderLineId = null}.
     */
    public CreateReservationRequest(UUID workorderLineId, UUID stockItemId, int requiredQuantity) {
        this(workorderLineId, null, stockItemId, requiredQuantity);
    }
}
