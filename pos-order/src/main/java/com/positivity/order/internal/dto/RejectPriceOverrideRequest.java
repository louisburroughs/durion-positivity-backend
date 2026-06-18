package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for rejecting a price override.
 */
@Data
@Schema(description = "Request payload for rejecting a pending price override")
public class RejectPriceOverrideRequest {

    @Schema(
            description = "Reason explaining why the override is being rejected",
            example = "Outside approved discount threshold",
            requiredMode = REQUIRED)
    @NotBlank(message = "Rejection reason is required")
    private String reason;

    @Schema(
            description = "Optional reviewer comments recorded with the rejection",
            example = "Discount exceeds policy limit for this product",
            requiredMode = NOT_REQUIRED)
    private String comments;
}
