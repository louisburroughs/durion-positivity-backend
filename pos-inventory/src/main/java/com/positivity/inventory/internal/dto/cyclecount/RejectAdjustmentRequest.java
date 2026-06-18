package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for rejecting a cycle count adjustment.
 */
@Schema(description = "Request to reject a pending cycle count adjustment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectAdjustmentRequest {

    @Schema(
            description = "Identifier of the user rejecting the adjustment",
            example = "user-30077",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rejector user ID is required")
    private String rejectorUserId;

    @Schema(
            description = "Reason explaining why the adjustment is being rejected",
            example = "Recount required before posting",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rejection reason is required")
    private String rejectionReason;
}
