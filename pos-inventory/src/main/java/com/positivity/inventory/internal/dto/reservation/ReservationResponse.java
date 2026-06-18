package com.positivity.inventory.internal.dto.reservation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Inventory reservation with its allocation state for a workorder line")
public class ReservationResponse {

    @Schema(
            description = "Identifier of the reservation",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID reservationId;

    @Schema(
            description = "Identifier of the workorder line the reservation fulfils",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderLineId;

    @Schema(
            description = "Identifier of the stock item being reserved",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID stockItemId;

    @Schema(
            description = "Quantity of the stock item required by the reservation",
            example = "4",
            requiredMode = REQUIRED)
    private int requiredQuantity;

    @Schema(
            description = "Quantity currently allocated against the reservation",
            example = "4",
            requiredMode = REQUIRED)
    private int allocatedQuantity;

    @Schema(
            description = "Status of the reservation, such as PENDING, ALLOCATED, or HARDENED",
            example = "ALLOCATED",
            requiredMode = REQUIRED)
    @NotNull
    private String status;
}
