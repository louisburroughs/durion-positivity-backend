package com.positivity.inventory.internal.dto.returns;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
                    + " this field is ignored",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private int quantityReturned;

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

    @JsonIgnore
    @AssertTrue(message = "quantityReturned must be positive when documentUom/documentQuantity are absent")
    public boolean isQuantitySourcePresent() {
        return quantityReturned > 0 || documentUom != null || documentQuantity != null;
    }

    @JsonIgnore
    @AssertTrue(message = "documentUom and documentQuantity must be provided together")
    public boolean isDocumentUomPairComplete() {
        return (documentUom == null) == (documentQuantity == null);
    }
}
