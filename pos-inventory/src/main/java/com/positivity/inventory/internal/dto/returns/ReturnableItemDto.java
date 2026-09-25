package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "An item eligible to be returned, with the quantity still returnable")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnableItemDto {
    @Schema(
            description = "Identifier of the returnable item; equal to workorderLineId, kept for compatibility"
                    + " with callers that read itemId rather than workorderLineId (they name the same work order"
                    + " line)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID itemId;

    @Schema(
            description = "Work order line this returnable quantity was issued against; identical to itemId",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderLineId;

    @Schema(description = "Stock keeping unit of the item", example = "SKU-10042", requiredMode = REQUIRED)
    private String sku;

    @Schema(
            description = "Human-readable description of the item",
            example = "Brake pad set, front axle",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Unit of measure the returnable quantity is expressed in (the product's base UoM); null"
                    + " when it cannot be resolved",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    private String uom;

    @Schema(
            description = "Quantity of the item that can still be returned: quantity consumed against this line"
                    + " minus quantity already returned against it, floored at 0",
            example = "4",
            requiredMode = REQUIRED)
    private int quantityReturnable;

    @Schema(
            description = "Identifier of the workorder the item was issued against",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID workorderId;
}
