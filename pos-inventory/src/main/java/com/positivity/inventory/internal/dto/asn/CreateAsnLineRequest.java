package com.positivity.inventory.internal.dto.asn;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
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

    @Schema(
            description = "Quantity of the SKU declared as shipped on the ASN, in the product's base UoM."
                    + " Required unless documentUom/documentQuantity are supplied, in which case the base quantity"
                    + " is derived and this field is ignored",
            example = "12",
            requiredMode = NOT_REQUIRED)
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

    @Schema(
            description = "Optional UoM the line is keyed in (e.g. CASE). When present, documentQuantity is"
                    + " converted to the product's base UoM at derivation time; a UoM with no conversion path is"
                    + " rejected with 422 UOM_CONVERSION_UNDEFINED",
            example = "CASE",
            requiredMode = NOT_REQUIRED)
    private String documentUom;

    @Schema(
            description = "Quantity shipped expressed in documentUom; must be supplied together with documentUom",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Positive
    private BigDecimal documentQuantity;

    @JsonIgnore
    @AssertTrue(message = "quantityShipped is required when documentUom/documentQuantity are absent")
    public boolean isQuantitySourcePresent() {
        return quantityShipped != null || documentUom != null || documentQuantity != null;
    }

    @JsonIgnore
    @AssertTrue(message = "documentUom and documentQuantity must be provided together")
    public boolean isDocumentUomPairComplete() {
        return (documentUom == null) == (documentQuantity == null);
    }
}
