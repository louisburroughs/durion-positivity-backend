package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A single line item returned as part of a purchase order")
public class PurchaseOrderLineResponse {

    @Schema(
            description = "Unique identifier of this purchase order line",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    @NotNull
    private UUID lineId;

    @Schema(
            description = "Sequential line number identifying this line within the purchase order",
            example = "1",
            requiredMode = REQUIRED)
    @NotNull
    private Integer lineNumber;

    @Schema(
            description = "Identifier of the SKU on this line",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private UUID skuId;

    @Schema(
            description = "Human-readable description of the item on this line",
            example = "Stainless steel water bottle, 750ml",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Quantity ordered on this line",
            example = "12",
            requiredMode = NOT_REQUIRED)
    private BigDecimal quantityDecimal;

    @Schema(
            description = "Unit cost of the item in the smallest currency unit (e.g. cents)",
            example = "1499",
            requiredMode = NOT_REQUIRED)
    private Long unitCostMinor;

    @Schema(
            description = "Total cost of this line in the smallest currency unit (e.g. cents)",
            example = "17988",
            requiredMode = NOT_REQUIRED)
    private Long lineTotalMinor;

    @Schema(
            description = "Tax amount for this line in the smallest currency unit (e.g. cents)",
            example = "1439",
            requiredMode = NOT_REQUIRED)
    private Long taxMinor;

    @Schema(
            description = "Quantity on this line not yet received",
            example = "4",
            requiredMode = NOT_REQUIRED)
    private BigDecimal openQuantityDecimal;
}
