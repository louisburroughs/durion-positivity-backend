package com.positivity.order.internal.dto.purchaseorder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.order.internal.enums.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregate view over every purchase order matching a filter — the whole population, not a page.
 *
 * <p>Exists because a paged list answers "show me some orders" and cannot answer "how many units
 * are on order" without the caller walking every page (#1798): 144 approved orders on alpha meant
 * the first page carried 20 of them and an aggregate read off it was wrong by a factor of seven.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Totals across all purchase orders matching the filter, with a per-status breakdown")
public class PurchaseOrderSummaryResponse {

    @Schema(description = "Vendor filter that was applied, if any")
    private UUID vendorId;

    @Schema(description = "Status filter that was applied, if any; absent means every status is included")
    private PurchaseOrderStatus status;

    @Schema(description = "Number of purchase orders matching the filter", requiredMode = REQUIRED)
    private long orderCount;

    @Schema(description = "Number of purchase-order lines across those orders", requiredMode = REQUIRED)
    private long lineCount;

    @Schema(description = "Units ordered across those lines", requiredMode = REQUIRED)
    private BigDecimal unitsOrdered;

    @Schema(description = "Units still open — ordered but not yet received", requiredMode = REQUIRED)
    private BigDecimal unitsOpen;

    @Schema(description = "Units received, derived as unitsOrdered minus unitsOpen", requiredMode = REQUIRED)
    private BigDecimal unitsReceived;

    @Schema(description = "Sum of order grand totals, in minor currency units", requiredMode = REQUIRED)
    private long grandTotalMinor;

    @Schema(description = "Sum of order open balances, in minor currency units", requiredMode = REQUIRED)
    private long openBalanceMinor;

    @Schema(
            description = "The same totals broken down by lifecycle status, in status order. Only statuses with "
                    + "at least one matching order appear.",
            requiredMode = REQUIRED)
    private List<PurchaseOrderStatusSummary> byStatus;
}
