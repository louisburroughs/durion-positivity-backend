package com.positivity.inventory.internal.dto.cyclecount;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
