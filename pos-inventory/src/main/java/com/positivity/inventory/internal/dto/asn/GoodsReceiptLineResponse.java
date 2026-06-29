package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Schema(
        description =
                "A single received line item on a goods receipt response, describing one SKU, its received quantity, and accrued cost")
@Data
@Builder
public class GoodsReceiptLineResponse {

    @Schema(
            description = "Unique identifier of the goods receipt line",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID receiptLineId;

    @Schema(
            description = "Identifier of the purchase order line this receipt line fulfills",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID poLineId;

    @Schema(
            description = "Stock keeping unit identifier for the received product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String sku;

    @Schema(description = "Quantity of the SKU received in this goods receipt", example = "8", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal quantityReceived;

    @Schema(
            description = "Unit cost of the received product expressed in minor currency units (e.g. cents)",
            example = "1499",
            requiredMode = NOT_REQUIRED)
    private Long unitCostMinor;

    @Schema(
            description =
                    "Total accrued cost for this line expressed in minor currency units (quantity multiplied by unit cost)",
            example = "11992",
            requiredMode = NOT_REQUIRED)
    private Long lineAccruedAmountMinor;
}
