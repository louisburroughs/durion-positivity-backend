package com.positivity.inventory.internal.dto.revaluation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for rejecting a pending revaluation (odoo-parity J4, issue #1054).
 *
 * <p>The rejecting actor is taken from the security context (ADR-0018), not from the body.
 */
@Schema(description = "Request to reject a pending revaluation; the SKU cost state is untouched")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectRevaluationRequest {

    @Schema(
            description = "Reason explaining why the revaluation is being rejected",
            example = "Recount disputed; leave standard price unchanged",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rejection reason is required")
    @Size(max = 1000, message = "Rejection reason must not exceed 1000 characters")
    private String rejectionReason;
}
