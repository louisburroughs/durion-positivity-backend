package com.positivity.inventory.internal.dto.scrap;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for rejecting a pending scrap document (odoo-parity D1, issue #1030).
 *
 * <p>The rejecting actor is taken from the security context (ADR-0018), not from the body.
 */
@Schema(description = "Request to reject a pending scrap document; no inventory changes are made")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectScrapRequest {

    @Schema(
            description = "Reason explaining why the scrap is being rejected",
            example = "Part was recovered and restocked",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rejection reason is required")
    @Size(max = 1000, message = "Rejection reason must not exceed 1000 characters")
    private String rejectionReason;
}
