package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to generate a pick list with its line items for a workorder")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePickListRequest {

    @Schema(
            description = "Identifier of the workorder the pick list is generated for",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Scheduled start time used to prioritize the generated pick list",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant scheduledStartAt;

    @Schema(
            description = "Base priority applied to the generated pick list; higher values are picked sooner",
            example = "5",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private int basePriority;

    @Schema(
            description = "Line items describing the SKUs and quantities to be picked",
            requiredMode = REQUIRED)
    @NotNull
    @Valid
    private List<PickLineItem> lineItems;

    @Schema(description = "A single SKU and quantity to be picked as part of a generated pick list")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PickLineItem {

        @Schema(
                description = "Identifier of the workorder line this pick fulfills",
                example = "01960003-0000-7000-8000-000000000021",
                requiredMode = REQUIRED)
        @NotNull
        private UUID workorderLineId;

        @Schema(
                description = "Identifier of the reservation backing this pick line, when applicable",
                example = "01960003-0000-7000-8000-000000000022",
                requiredMode = NOT_REQUIRED)
        private UUID reservationId;

        @Schema(
                description = "Stock keeping unit code to be picked",
                example = "SKU-10042",
                requiredMode = REQUIRED)
        @NotBlank
        private String sku;

        @Schema(
                description = "Quantity of the SKU to be picked",
                example = "12",
                requiredMode = REQUIRED)
        @Positive
        private int quantity;
    }
}
