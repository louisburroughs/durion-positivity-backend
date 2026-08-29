package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

/** A single opening-stock line within a bulk ingest request. */
@Data
@Schema(description = "One product's opening on-hand quantity at one storage location")
public class OpeningStockBulkIngestRecord {

    @Schema(description = "SKU of the product being stocked", example = "MOBI-120764", requiredMode = REQUIRED)
    @NotBlank
    private String sku;

    @Schema(
            description = "Storage location the stock sits in. Defaults to the request's locationId when omitted,"
                    + " which is only useful for a site with no storage topology.",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Opening quantity. Must be positive: this establishes stock that is not there yet,"
                    + " so a zero or negative line has nothing to establish and is rejected rather than"
                    + " silently posting a no-op or a withdrawal.",
            example = "24",
            requiredMode = REQUIRED)
    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal quantity;

    @Schema(description = "Unit of measure for the quantity", example = "EA", requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Ledger reason code; defaults to OPENING_BALANCE when omitted",
            example = "OPENING_BALANCE",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;
}
