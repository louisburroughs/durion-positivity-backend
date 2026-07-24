package com.positivity.inventory.internal.dto.valuation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One SKU-level row of the inventory valuation read model (odoo-parity J2, issue #1052).
 *
 * <p>{@code onHandValue = onHand × unitCostCurrent}, where {@code unitCostCurrent} is the SKU's
 * current unit cost derived from its running {@code sku_cost_state} per the SKU's resolved costing
 * method (AVERAGE → running weighted-average; STANDARD → configured standard price, falling back to
 * the latest-receipt-cost memo). An uncosted SKU (no cost state yet, or a STANDARD SKU with neither
 * a standard price nor a receipt) surfaces {@code unitCostCurrent = null}, {@code uncosted = true},
 * and {@code onHandValue = 0} — never an error.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "SKU-level inventory valuation row")
public class ValuationRow {

    @Schema(description = "SKU / stock-item identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private String stockItemId;

    @Schema(
            description = "On-hand quantity contributing to the value (site-scoped when a location filter is applied)",
            example = "20",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private long onHand;

    @Schema(
            description = "Resolved costing method for the SKU (AVERAGE or STANDARD)",
            example = "AVERAGE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String costingMethod;

    @Schema(
            description = "Current unit cost per the resolved method; null when the SKU is uncosted",
            example = "6.0000",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal unitCostCurrent;

    @Schema(
            description = "On-hand value: onHand × unitCostCurrent (0 when uncosted)",
            example = "120.0000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal onHandValue;

    @Schema(
            description = "True when the SKU has no derivable unit cost, so its value is reported as 0",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean uncosted;
}
