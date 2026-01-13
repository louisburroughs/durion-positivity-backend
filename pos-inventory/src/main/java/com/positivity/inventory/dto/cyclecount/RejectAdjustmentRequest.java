package com.positivity.inventory.dto.cyclecount;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Request DTO for rejecting a cycle count adjustment.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectAdjustmentRequest {
    
    @NotBlank(message = "Rejector user ID is required")
    private String rejectorUserId;
    
    @NotBlank(message = "Rejection reason is required")
    private String rejectionReason;
}
