package com.positivity.inventory.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Optional filter criteria for listing purchase orders")
public class ListPurchaseOrdersRequest {

    @Schema(
            description = "Filter to purchase orders placed with this vendor",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID vendorId;

    @Schema(
            description = "Filter to purchase orders in this lifecycle status",
            example = "APPROVED",
            requiredMode = NOT_REQUIRED)
    private PurchaseOrderStatus status;

    @Schema(
            description = "Filter to purchase orders denominated in this ISO 4217 currency code",
            example = "USD",
            requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(
            description = "Filter to purchase orders shipping to this location",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;
}
