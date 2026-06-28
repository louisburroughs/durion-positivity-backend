package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Schema(
        description =
                "Request payload describing a single line item to include when creating an advance shipping notice (ASN)")
@Data
public class CreateAsnLineRequest {

    @Schema(
            description = "Identifier of the purchase order this ASN line is associated with",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID poId;

    @Schema(
            description = "Identifier of the specific purchase order line this ASN line fulfills",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID poLineId;

    @Schema(
            description = "Stock keeping unit identifier for the shipped product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    @NotBlank
    private String sku;

    @Schema(description = "Quantity of the SKU declared as shipped on the ASN", example = "12", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal quantityShipped;

    @Schema(description = "Unit of measure for the shipped quantity", example = "EA", requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Unit cost of the product expressed in minor currency units (e.g. cents)",
            example = "1499",
            requiredMode = NOT_REQUIRED)
    @Positive
    private Long unitCostMinor;

    @Schema(
            description = "Lot or batch number associated with the shipped product",
            example = "LOT-2026-0042",
            requiredMode = NOT_REQUIRED)
    private String lotNumber;
}
