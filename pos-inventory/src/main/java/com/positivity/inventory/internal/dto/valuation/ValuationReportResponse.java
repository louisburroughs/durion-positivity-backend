package com.positivity.inventory.internal.dto.valuation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inventory valuation report (odoo-parity J2, issue #1052): SKU-level on-hand value rows plus the
 * total. Optionally site-scoped ({@code locationId}) and/or point-in-time ({@code asOf}).
 *
 * <p>The current-state report sources each SKU's unit cost from its running {@code sku_cost_state}
 * (maintained by the J1 costing engine). The as-of variant reconstructs each SKU's running cost by
 * replaying its on-hand-affecting ledger entries up to the instant through the same costing
 * strategy (pairs with the A3 as-of on-hand reconstruction), so the reported value equals the
 * historical Σ(qty × method-derived unit cost) at that point.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Inventory valuation report with SKU-level rows and a total")
public class ValuationReportResponse {

    @Schema(
            description = "Location filter applied, or null for all sites",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Historical instant the valuation was reconstructed at, or null for current state",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant asOf;

    @Schema(description = "SKU-level valuation rows", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ValuationRow> rows;

    @Schema(
            description = "Sum of onHandValue across all rows (uncosted rows contribute 0)",
            example = "120.0000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal totalOnHandValue;
}
