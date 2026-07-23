package com.positivity.inventory.internal.dto.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One SKU line of a transfer order response (odoo-parity C1, issue #1035).
 */
@Schema(description = "SKU line of a transfer order with requested/dispatched/received quantities")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferOrderLineResponse {

    @Schema(description = "Line identifier")
    private UUID lineId;

    @Schema(description = "Position of the line within the order (1-based)")
    private Integer lineNumber;

    @Schema(description = "SKU / stock item identifier")
    private String sku;

    @Schema(description = "Quantity requested for transfer")
    private Integer requestedQty;

    @Schema(description = "Quantity dispatched from the source so far")
    private Integer dispatchedQty;

    @Schema(description = "Quantity received at the destination so far")
    private Integer receivedQty;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Last update timestamp")
    private Instant updatedAt;
}
