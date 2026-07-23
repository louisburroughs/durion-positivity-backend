package com.positivity.inventory.internal.dto.scrap;

import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.ScrapCostSource;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for scrap/write-off documents (odoo-parity D1, issue #1030).
 */
@Schema(description = "Scrap document with its approval and posting state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapResponse {

    @Schema(description = "Scrap document identifier")
    private UUID scrapId;

    @Schema(description = "SKU / stock item identifier")
    private String stockItemId;

    @Schema(description = "Quantity written off")
    private Integer quantity;

    @Schema(description = "Location the stock is scrapped from")
    private UUID locationId;

    @Schema(description = "Bin-level storage location, when bin-scoped")
    private UUID storageLocationId;

    @Schema(description = "Scrap reason")
    private ScrapReasonCode reasonCode;

    @Schema(description = "Free-text notes")
    private String notes;

    @Schema(description = "Linked workorder, when the part was damaged during a job")
    private UUID workorderId;

    @Schema(description = "Lot the scrapped units belong to (LOT-tracked SKUs)")
    private UUID lotId;

    @Schema(description = "Photo/attachment reference")
    private String attachmentReference;

    @Schema(description = "Unit cost snapshot at creation; null when unknown")
    private BigDecimal unitCostSnapshot;

    @Schema(description = "Origin of the cost snapshot (LATEST_RECEIPT or NONE)")
    private ScrapCostSource costSource;

    @Schema(description = "Monetary value of the scrap (quantity x cost snapshot); null when cost is unknown")
    private BigDecimal scrapValue;

    @Schema(description = "Whether posting triggers a pick-face replenishment evaluation")
    private Boolean shouldReplenish;

    @Schema(description = "Lifecycle status")
    private ScrapStatus status;

    @Schema(description = "Required approval tier; null when auto-approved")
    private ApprovalTier requiredApprovalTier;

    @Schema(description = "User who created the scrap")
    private String createdBy;

    @Schema(description = "User who approved the scrap (SYSTEM for auto-approval)")
    private String approvedBy;

    @Schema(description = "User who rejected the scrap")
    private String rejectedBy;

    @Schema(description = "Reason the scrap was rejected")
    private String rejectionReason;

    @Schema(description = "SCRAP_OUT ledger entry created when posted")
    private UUID ledgerEntryId;

    @Schema(description = "Error message when status is FAILED")
    private String errorMessage;

    @Schema(description = "Creation timestamp")
    private Instant createdAt;

    @Schema(description = "Last update timestamp")
    private Instant updatedAt;

    @Schema(description = "Approval timestamp")
    private Instant approvedAt;

    @Schema(description = "Rejection timestamp")
    private Instant rejectedAt;

    @Schema(description = "Ledger posting timestamp")
    private Instant postedAt;
}
