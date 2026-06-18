package com.positivity.inventory.internal.dto.reservation;

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
@Schema(description = "Request to create an inventory reservation against a workorder line for a stock item")
public class CreateReservationRequest {

    @Schema(
            description = "Identifier of the workorder line the reservation fulfils",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderLineId;

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
}
