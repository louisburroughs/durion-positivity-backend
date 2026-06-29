package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single line item on a purchase order request, identifying the SKU, quantity, and unit cost")
public class PurchaseOrderLineRequest {

    @Schema(
            description = "Sequential line number identifying this line within the purchase order",
            example = "1",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private Integer lineNumber;

    @Schema(
            description = "Identifier of the SKU being ordered on this line",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = NOT_REQUIRED)
    private UUID skuId;

    @Schema(
            description = "Human-readable description of the item being ordered",
            example = "Stainless steel water bottle, 750ml",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String description;

    @Schema(description = "Quantity of the item being ordered on this line", example = "12", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal quantity;

    @Schema(
            description = "Unit cost of the item in the smallest currency unit (e.g. cents)",
            example = "1499",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private Long unitCostMinor;

    @Schema(
            description = "Identifier of the tax code applied to this line",
            example = "TAX-STD",
            requiredMode = NOT_REQUIRED)
    private String taxCodeId;

    @Schema(
            description = "Identifier of the general ledger account this line posts to",
            example = "GL-5000",
            requiredMode = NOT_REQUIRED)
    private String glAccountId;
}
