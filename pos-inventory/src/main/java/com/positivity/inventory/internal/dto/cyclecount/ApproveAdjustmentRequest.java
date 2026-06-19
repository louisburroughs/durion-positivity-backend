package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for approving a cycle count adjustment.
 */
@Schema(description = "Request to approve a pending cycle count adjustment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveAdjustmentRequest {

    /**
     * Legacy client-provided value; service layer resolves authoritative actor from
     * authenticated context.
     */
    @Schema(
            description = "Legacy client-provided approver identifier; the authoritative actor is resolved"
                    + " server-side from the authenticated context",
            example = "user-20055",
            requiredMode = NOT_REQUIRED)
    private String approverUserId;

    /**
     * Optional notes from the approver.
     */
    @Schema(
            description = "Optional free-text notes recorded with the approval",
            example = "Variance confirmed against receiving log",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
