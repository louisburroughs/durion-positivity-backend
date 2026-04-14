package com.positivity.inventory.internal.dto.cyclecount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for approving a cycle count adjustment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveAdjustmentRequest {

    /**
     * Legacy client-provided value; service layer resolves authoritative actor from
     * authenticated context.
     */
    private String approverUserId;

    /**
     * Optional notes from the approver.
     */
    private String notes;
}
