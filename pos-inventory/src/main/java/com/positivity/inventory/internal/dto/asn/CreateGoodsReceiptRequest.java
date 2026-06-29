package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Schema(
        description =
                "Request payload to record a goods receipt for inventory received against a purchase order, optionally linked to an ASN")
@Data
public class CreateGoodsReceiptRequest {

    @Schema(
            description = "Identifier of the purchase order the goods are received against",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID poId;

    @Schema(
            description = "Identifier of the advance shipping notice (ASN) this receipt fulfills, if applicable",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID asnId;

    @Schema(
            description = "Identifier of the location where the goods are received into inventory",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "Received line items detailing the SKUs and quantities in this goods receipt",
            requiredMode = REQUIRED)
    @NotNull
    private List<@Valid CreateGoodsReceiptLineRequest> lines;
}
