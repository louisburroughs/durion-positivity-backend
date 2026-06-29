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
                "A single line item on an advance shipping notice (ASN) response, describing one SKU and its shipped/received quantities")
@Data
@Builder
public class AsnLineResponse {

    @Schema(
            description = "Unique identifier of the ASN line item",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID asnLineId;

    @Schema(
            description = "Identifier of the purchase order this ASN line is associated with",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID poId;

    @Schema(
            description = "Stock keeping unit identifier for the shipped product",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String sku;

    @Schema(description = "Quantity of the SKU declared as shipped on the ASN", example = "12", requiredMode = REQUIRED)
    @NotNull
    private BigDecimal quantityShipped;

    @Schema(
            description = "Quantity of the SKU received against this ASN line so far",
            example = "8",
            requiredMode = NOT_REQUIRED)
    private BigDecimal quantityReceived;

    @Schema(
            description = "Unit of measure for the shipped and received quantities",
            example = "EA",
            requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Lot or batch number associated with the shipped product",
            example = "LOT-2026-0042",
            requiredMode = NOT_REQUIRED)
    private String lotNumber;
}
