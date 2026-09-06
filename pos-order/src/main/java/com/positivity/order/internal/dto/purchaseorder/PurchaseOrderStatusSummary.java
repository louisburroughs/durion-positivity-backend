package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.order.internal.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "Purchase-order totals for one lifecycle status")
public record PurchaseOrderStatusSummary(
        @Schema(description = "Lifecycle status these totals cover", requiredMode = REQUIRED)
        PurchaseOrderStatus status,

        @Schema(description = "Number of purchase orders in this status", requiredMode = REQUIRED)
        long orderCount,

        @Schema(description = "Number of purchase-order lines across those orders", requiredMode = REQUIRED)
        long lineCount,

        @Schema(description = "Units ordered across those lines (sum of quantityDecimal)", requiredMode = REQUIRED)
        BigDecimal unitsOrdered,

        @Schema(
                description =
                        "Units still open (sum of openQuantityDecimal). Outstanding supply only for APPROVED / "
                                + "PARTIALLY_RECEIVED; a CANCELLED or DRAFT row keeps its open quantity but nothing is on order.",
                requiredMode = REQUIRED)
        BigDecimal unitsOpen,

        @Schema(
                description = "Units received, derived as unitsOrdered minus unitsOpen. The authoritative "
                        + "received quantity is pos-inventory's goods receipt; this is the order-side view.",
                requiredMode = REQUIRED)
        BigDecimal unitsReceived,

        @Schema(description = "Sum of order grand totals, in minor currency units", requiredMode = REQUIRED)
        long grandTotalMinor,

        @Schema(description = "Sum of order open balances, in minor currency units", requiredMode = REQUIRED)
        long openBalanceMinor) {}
