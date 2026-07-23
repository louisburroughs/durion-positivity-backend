package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Received quantity recorded against a single receiving session line")
public class ReceiveLineRequest {
    @Schema(
            description = "Identifier of the receiving line being recorded",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "lineId is required")
    private UUID lineId;

    @Schema(
            description = "Quantity actually received for the line, in the product's base UoM; may be zero."
                    + " Required unless documentUom/documentQuantity are supplied, in which case the base quantity"
                    + " is derived and this field is ignored",
            example = "8",
            requiredMode = NOT_REQUIRED)
    @DecimalMin(value = "0.0", inclusive = true, message = "receivedQuantity must be >= 0")
    private BigDecimal receivedQuantity;

    @Schema(
            description = "Lot or batch number of the received stock. Required (422 LOT_NUMBER_REQUIRED) when the"
                    + " product is LOT-tracked per the catalog replica; ignored for tracking purposes otherwise",
            example = "LOT-2026-0042",
            requiredMode = NOT_REQUIRED)
    private String lotNumber;

    @Schema(
            description = "Optional UoM the line is keyed in (e.g. CASE). When present, documentQuantity is"
                    + " converted to the product's base UoM at posting time; a UoM with no conversion path is"
                    + " rejected with 422 UOM_CONVERSION_UNDEFINED",
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
    @AssertTrue(message = "receivedQuantity is required when documentUom/documentQuantity are absent")
    public boolean isQuantitySourcePresent() {
        return receivedQuantity != null || documentUom != null || documentQuantity != null;
    }

    @JsonIgnore
    @AssertTrue(message = "documentUom and documentQuantity must be provided together")
    public boolean isDocumentUomPairComplete() {
        return (documentUom == null) == (documentQuantity == null);
    }
}
