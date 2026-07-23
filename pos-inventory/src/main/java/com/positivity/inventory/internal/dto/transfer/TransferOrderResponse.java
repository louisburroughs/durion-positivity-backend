package com.positivity.inventory.internal.dto.transfer;

import com.positivity.inventory.internal.enums.TransferOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for a transfer order (odoo-parity C1, issue #1035).
 */
@Schema(description = "Cross-site transfer order with lifecycle status and quantity lines")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransferOrderResponse {

    @Schema(description = "Transfer order identifier")
    private UUID transferOrderId;

    @Schema(description = "Source site the transfer ships from")
    private UUID sourceLocationId;

    @Schema(description = "Bin-level source storage location, when bin-scoped")
    private UUID sourceStorageLocationId;

    @Schema(description = "Destination site the transfer ships to")
    private UUID destinationLocationId;

    @Schema(description = "Bin-level destination storage location, when bin-scoped")
    private UUID destinationStorageLocationId;

    @Schema(description = "Lifecycle status")
    private TransferOrderStatus status;

    @Schema(description = "SKU lines")
    private List<TransferOrderLineResponse> lines;

    @Schema(description = "Free-text notes")
    private String notes;

    @Schema(description = "User who created the order")
    private String createdBy;

    @Schema(description = "User who approved the order, when the approval step is enabled")
    private String approvedBy;

    @Schema(description = "User who dispatched the order")
    private String dispatchedBy;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Last update timestamp")
    private Instant updatedAt;
}
