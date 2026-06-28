package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Result of receiving goods against a purchase order, including updated status and open balances")
public class ReceivePurchaseOrderResponse {

    @Schema(
            description = "Identifier of the purchase order that was received against",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID poId;

    @Schema(
            description = "Updated lifecycle status of the purchase order after the receipt",
            example = "PARTIALLY_RECEIVED",
            requiredMode = REQUIRED)
    @NotNull
    private PurchaseOrderStatus status;

    @Schema(
            description = "Remaining open balance in the smallest currency unit (e.g. cents) after the receipt",
            example = "5000",
            requiredMode = NOT_REQUIRED)
    private Long openBalanceMinor;

    @Schema(
            description = "Human-readable message summarising the outcome of the receipt",
            example = "Received 4 of 12 units; purchase order partially received",
            requiredMode = NOT_REQUIRED)
    private String message;

    @Schema(description = "Per-line detail of remaining open quantities after the receipt", requiredMode = NOT_REQUIRED)
    private List<ReceivePurchaseOrderLineDetail> lines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Open-quantity detail for a single purchase order line after a receipt")
    public static class ReceivePurchaseOrderLineDetail {

        @Schema(
                description = "Identifier of the purchase order line",
                example = "01960003-0000-7000-8000-000000000020",
                requiredMode = REQUIRED)
        @NotNull
        private UUID lineId;

        @Schema(
                description = "Quantity on this line still awaiting receipt",
                example = "8",
                requiredMode = NOT_REQUIRED)
        private BigDecimal openQuantityDecimal;
    }
}
