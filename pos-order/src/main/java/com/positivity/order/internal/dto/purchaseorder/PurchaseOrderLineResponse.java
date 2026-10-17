package com.positivity.order.internal.dto.purchaseorder;

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

    @Schema(description = "Quantity ordered on this line", example = "12", requiredMode = NOT_REQUIRED)
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
            description = "Quantity on this line the vendor still owes: ordered − received − returned − cancelled,"
                    + " never negative (#2635)",
            example = "4",
            requiredMode = NOT_REQUIRED)
    private BigDecimal openQuantityDecimal;

    @Schema(
            description = "Base units received, net of receipt corrections and returns (#2635)",
            example = "6",
            requiredMode = NOT_REQUIRED)
    private BigDecimal receivedQuantityDecimal;

    @Schema(
            description = "Base units returned to the vendor for credit; a reopen for replacement takes them back"
                    + " (#2635)",
            example = "0",
            requiredMode = NOT_REQUIRED)
    private BigDecimal returnedQuantityDecimal;

    @Schema(
            description = "Base units short-closed: the vendor will not ship them (#2635)",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private BigDecimal cancelledQuantityDecimal;

    @Schema(
            description = "Tax code the line was written with; null when none. Returned so a read, edited and revised"
                    + " line keeps it (#2681)",
            example = "TAX-STD",
            requiredMode = NOT_REQUIRED)
    private String taxCodeId;

    @Schema(
            description = "GL account the line was written with; null when none. Returned so a read, edited and"
                    + " revised line keeps it (#2681)",
            example = "5000",
            requiredMode = NOT_REQUIRED)
    private String glAccountId;

    @Schema(
            description = "UoM the line was keyed in when it differed from the product's base UoM",
            example = "CASE",
            requiredMode = NOT_REQUIRED)
    private String documentUom;

    @Schema(
            description = "Quantity as keyed in documentUom; quantityDecimal holds the derived base quantity",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private BigDecimal documentQuantity;

    @Schema(
            description = "Effective base-per-document-unit conversion factor applied at derivation time",
            example = "12",
            requiredMode = NOT_REQUIRED)
    private BigDecimal conversionFactor;
}
