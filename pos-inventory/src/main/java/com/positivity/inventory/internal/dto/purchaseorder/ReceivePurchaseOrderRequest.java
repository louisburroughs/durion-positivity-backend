package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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
                description = "Quantity received for this line",
                example = "4",
                requiredMode = REQUIRED)
        @NotNull
        @Positive
        @JsonAlias("receivedQty")
        private BigDecimal quantityReceived;

        @Schema(
                description = "Actual unit cost of the received goods in the smallest currency unit (e.g. cents)",
                example = "1499",
                requiredMode = NOT_REQUIRED)
        private Long unitCostMinor;
    }
}
