package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Approval or rejection status for a specific line item")
public class LineItemApprovalDto {

    @NotNull(message = "lineItemId is required")
    @Schema(
            description = "ID of the line item (service/product) being approved or rejected",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    private UUID lineItemId;

    @NotNull(message = "approved status is required")
    @Schema(
            description = "Whether this line item is approved (true) or rejected (false)",
            example = "true",
            requiredMode = REQUIRED)
    private Boolean approved;

    @Schema(
            description = "Reason for rejection (required if approved=false)",
            example = "Customer declined optional service",
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;

    @Schema(
            description = "Additional notes about this line item decision",
            example = "Customer wants to get second opinion",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
