package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Schema(
        description =
                "Request payload describing a single received line item when recording a goods receipt against a purchase order")
@Data
public class CreateGoodsReceiptLineRequest {

    @Schema(
            description = "Identifier of the specific purchase order line this receipt line fulfills",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID poLineId;

    @Schema(
            description = "Stock keeping unit identifier for the received product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String sku;

    @Schema(
            description = "Whole-unit quantity of the SKU received in this goods receipt",
            example = "8",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    @Digits(integer = 10, fraction = 0)
    private BigDecimal quantityReceived;

    @Schema(
            description = "Unit cost of the received product expressed in minor currency units (e.g. cents)",
            example = "1499",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private Long unitCostMinor;

    @Schema(
            description = "Lot or batch number associated with the received product",
            example = "LOT-2026-0042",
            requiredMode = NOT_REQUIRED)
    private String lotNumber;
}
