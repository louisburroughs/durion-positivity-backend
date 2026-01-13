package com.positivity.inventory.dto.cyclecount;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Request DTO for approving a cycle count adjustment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveAdjustmentRequest {
    
    @NotBlank(message = "Approver user ID is required")
    private String approverUserId;
    
    /**
     * Optional notes from the approver.
     */
    private String notes;
}
