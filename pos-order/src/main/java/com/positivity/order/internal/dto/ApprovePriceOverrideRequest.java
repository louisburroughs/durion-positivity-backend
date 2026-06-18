package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Request DTO for approving a price override.
 */
@Data
@Schema(description = "Request payload for approving a pending price override")
public class ApprovePriceOverrideRequest {

    @Schema(
            description = "Optional approver comments recorded with the approval",
            example = "Approved per store manager discretion",
            requiredMode = NOT_REQUIRED)
    private String comments;
}
