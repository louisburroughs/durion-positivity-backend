package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response describing a picked item resulting from a pick task")
public class WorkorderPickedItemResponse {

    @Schema(
            description = "Identifier of the pick task that produced this item",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID pickTaskId;

    @Schema(
            description = "Identifier of the pick list the task belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID pickListId;

    @Schema(
            description = "Identifier of the SKU that was picked",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID skuId;

    @Schema(description = "Quantity picked", example = "2", requiredMode = REQUIRED)
    private int qtyPicked;

    @Schema(description = "Quantity consumed from the picked amount", example = "2", requiredMode = REQUIRED)
    private int qtyConsumed;

    @Schema(description = "Quantity remaining from the picked amount", example = "2", requiredMode = REQUIRED)
    private int qtyRemaining;

    @Schema(description = "Status of the picked item", example = "PICKED", requiredMode = REQUIRED)
    private String status;
}
