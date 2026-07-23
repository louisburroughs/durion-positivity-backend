package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
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
            description = "Whole-unit quantity of the SKU received, in the product's base UoM. Required unless"
                    + " documentUom/documentQuantity are supplied, in which case the base quantity is derived and"
                    + " this field is ignored",
            example = "8",
            requiredMode = NOT_REQUIRED)
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

    @Schema(
            description = "Optional UoM the receipt line is keyed in (e.g. CASE). When present, documentQuantity"
                    + " is converted to the product's base UoM at posting time and the ledger posts the base"
                    + " quantity; unitCostMinor then refers to one documentUom unit. A UoM with no conversion"
                    + " path is rejected with 422 UOM_CONVERSION_UNDEFINED",
            example = "CASE",
            requiredMode = NOT_REQUIRED)
    private String documentUom;

    @Schema(
            description = "Quantity received expressed in documentUom; must be supplied together with documentUom",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Positive
    private BigDecimal documentQuantity;

    @JsonIgnore
    @AssertTrue(message = "quantityReceived is required when documentUom/documentQuantity are absent")
    public boolean isQuantitySourcePresent() {
        return quantityReceived != null || documentUom != null || documentQuantity != null;
    }

    @JsonIgnore
    @AssertTrue(message = "documentUom and documentQuantity must be provided together")
    public boolean isDocumentUomPairComplete() {
        return (documentUom == null) == (documentQuantity == null);
    }
}
