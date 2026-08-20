package com.positivity.inventory.internal.dto.backorder;

import com.positivity.inventory.internal.enums.BackorderResolutionSource;
import com.positivity.inventory.internal.enums.BackorderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * API view of a {@link com.positivity.inventory.internal.entity.BackorderRecord} (odoo-parity G1,
 * issue #1046).
 */
@Data
@Builder
@Schema(description = "A backorder: unfulfilled workorder-line demand held open until availability covers it")
public class BackorderResponse {

    @Schema(description = "Backorder record identifier")
    private UUID backorderId;

    @Schema(description = "Workorder line whose demand was short, when demand was from a workorder; null otherwise")
    private UUID workorderLineId;

    @Schema(
            description =
                    "Sales-order line whose demand was short (CAP #1315), when demand was from a sales order; null"
                            + " otherwise")
    private UUID salesOrderLineId;

    @Schema(description = "SKU / stock-item identifier that was short")
    private String sku;

    @Schema(description = "Site the demand is short at")
    private UUID locationId;

    @Schema(description = "Quantity that could not be fulfilled (positive)")
    private BigDecimal quantityShort;

    @Schema(
            description = "The stock item's base unit of measure, when sku resolves to a catalog product with a"
                    + " declared UoM; null otherwise")
    private String unitOfMeasure;

    @Schema(description = "Lifecycle status: OPEN, RESOLVED, or CANCELLED")
    private BackorderStatus status;

    @Schema(description = "What drove resolution; null while OPEN or CANCELLED")
    private BackorderResolutionSource resolutionSource;

    @Schema(description = "Actor that opened the backorder")
    private String createdBy;

    @Schema(description = "When the backorder was opened")
    private Instant createdAt;

    @Schema(description = "When the backorder was last updated")
    private Instant updatedAt;

    @Schema(description = "When the backorder was resolved; null otherwise")
    private Instant resolvedAt;
}
