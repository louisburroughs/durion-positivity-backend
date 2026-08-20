package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "A single line of a return request: the SKU and the quantity being returned")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemLine {

    @Schema(
            description = "Identifier of the SKU being returned",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID skuId;

    @Schema(
            description = "Quantity of the SKU being returned, in the product's base UoM. Required unless"
                    + " documentUom/documentQuantity are supplied, in which case the base quantity is derived and"
                    + " this field is ignored. May carry decimals only to the precision_scale the product's"
                    + " catalog declaration allows (422 FRACTIONAL_QUANTITY_NOT_ALLOWED otherwise)",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private BigDecimal quantityReturned;

    @Schema(
            description = "Optional UoM the return line is keyed in (e.g. CASE). When present, documentQuantity"
                    + " is converted to the product's base UoM at posting time; a UoM with no conversion path is"
                    + " rejected with 422 UOM_CONVERSION_UNDEFINED",
            example = "CASE",
            requiredMode = NOT_REQUIRED)
    private String documentUom;

    @Schema(
            description = "Quantity returned expressed in documentUom; must be supplied together with documentUom",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @Positive
    private BigDecimal documentQuantity;

    @Schema(
            description = "Lot number the returned units belong to. Required (422 LOT_NUMBER_REQUIRED) when the SKU"
                    + " is LOT-tracked and must reference an existing lot (422 LOT_UNKNOWN) — a CONSUMED lot is"
                    + " legitimate (returning stock flips it back to ACTIVE). Ignored for untracked SKUs",
            example = "LOT-2026-A",
            requiredMode = NOT_REQUIRED)
    @Size(max = 128)
    private String lotNumber;

    /** Pre-E2 arity kept for existing callers/tests: no lot number keyed. */
    public ReturnItemLine(UUID skuId, BigDecimal quantityReturned, String documentUom, BigDecimal documentQuantity) {
        this(skuId, quantityReturned, documentUom, documentQuantity, null);
    }

    @JsonIgnore
    @AssertTrue(message = "quantityReturned must be positive when documentUom/documentQuantity are absent")
    public boolean isQuantitySourcePresent() {
        return (quantityReturned != null && quantityReturned.signum() > 0)
                || documentUom != null
                || documentQuantity != null;
    }

    @JsonIgnore
    @AssertTrue(message = "documentUom and documentQuantity must be provided together")
    public boolean isDocumentUomPairComplete() {
        return (documentUom == null) == (documentQuantity == null);
    }
}
