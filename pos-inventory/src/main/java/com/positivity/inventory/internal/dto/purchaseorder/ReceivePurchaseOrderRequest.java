package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record receipt of goods against one or more purchase order lines")
public class ReceivePurchaseOrderRequest {

    @Schema(
            description = "Line-level receipts describing the quantity received against each purchase order line",
            requiredMode = REQUIRED)
    @NotNull
    @NotEmpty
    @Valid
    @JsonAlias("lineReceipts")
    private List<ReceivePurchaseOrderLineRequest> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Receipt detail for a single purchase order line")
    public static class ReceivePurchaseOrderLineRequest {

        @Schema(
                description = "Identifier of the purchase order line being received",
                example = "01960003-0000-7000-8000-000000000020",
                requiredMode = REQUIRED)
        @NotNull
        private UUID lineId;

        @Schema(
                description = "Quantity received for this line, in the product's base UoM. Required unless"
                        + " documentUom/documentQuantity are supplied, in which case the base quantity is derived"
                        + " and this field is ignored",
                example = "4",
                requiredMode = NOT_REQUIRED)
        @Positive
        @JsonAlias("receivedQty")
        private BigDecimal quantityReceived;

        @Schema(
                description = "Actual unit cost of the received goods in the smallest currency unit (e.g. cents)."
                        + " When documentUom is present this refers to one documentUom unit",
                example = "1499",
                requiredMode = NOT_REQUIRED)
        private Long unitCostMinor;

        @Schema(
                description = "Optional UoM the receipt is keyed in (e.g. CASE). When present, documentQuantity"
                        + " is converted to the product's base UoM at posting time; a UoM with no conversion path"
                        + " is rejected with 422 UOM_CONVERSION_UNDEFINED",
                example = "CASE",
                requiredMode = NOT_REQUIRED)
        private String documentUom;

        @Schema(
                description =
                        "Quantity received expressed in documentUom; must be supplied together with" + " documentUom",
                example = "1",
                requiredMode = NOT_REQUIRED)
        @Positive
        private BigDecimal documentQuantity;

        @Schema(
                description = "Lot or batch number of the received stock. Required (422 LOT_NUMBER_REQUIRED) when"
                        + " the line's product is LOT-tracked per the catalog replica; ignored for tracking"
                        + " purposes otherwise",
                example = "LOT-2026-0042",
                requiredMode = NOT_REQUIRED)
        private String lotNumber;

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
}
