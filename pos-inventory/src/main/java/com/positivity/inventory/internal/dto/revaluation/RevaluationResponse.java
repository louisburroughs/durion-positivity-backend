package com.positivity.inventory.internal.dto.revaluation;

import com.positivity.inventory.internal.enums.ApprovalTier;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for a manual cost-revaluation document (odoo-parity J4, issue #1054).
 */
@Schema(description = "A manual cost-revaluation document with its approval/application state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevaluationResponse {

    @Schema(description = "Revaluation document identifier")
    private UUID revaluationId;

    @Schema(description = "SKU / stock item identifier that was revalued")
    private String stockItemId;

    @Schema(description = "Resolved costing method whose cost was corrected")
    private CostingMethod costingMethod;

    @Schema(description = "Unit cost before the revaluation; null when the SKU was uncosted")
    private BigDecimal previousUnitCost;

    @Schema(description = "Corrected unit cost")
    private BigDecimal newUnitCost;

    @Schema(description = "On-hand quantity the engine had costed at revaluation time (delta basis)")
    private long onHandQuantity;

    @Schema(description = "Signed inventory value change: (newUnitCost - previousUnitCost) x onHandQuantity")
    private BigDecimal valueDelta;

    @Schema(description = "Justification for the correction")
    private String reason;

    @Schema(description = "Lifecycle status")
    private RevaluationStatus status;

    @Schema(description = "Required approval tier; null when auto-applied")
    private ApprovalTier requiredApprovalTier;

    @Schema(description = "Actor who submitted the revaluation")
    private String createdBy;

    @Schema(description = "Actor who approved and applied the revaluation")
    private String approvedBy;

    @Schema(description = "Actor who rejected the revaluation")
    private String rejectedBy;

    @Schema(description = "Reason the revaluation was rejected")
    private String rejectionReason;

    @Schema(description = "When the document was created")
    private Instant createdAt;

    @Schema(description = "When the document was last updated")
    private Instant updatedAt;

    @Schema(description = "When the revaluation was applied to the cost state")
    private Instant appliedAt;

    @Schema(description = "When the revaluation was rejected")
    private Instant rejectedAt;
}
