package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Response returned when an inventory adjustment request is created.
 *
 * Issue: CAP-215 Story #37
 */
@Schema(description = "Response returned when an inventory adjustment request is created")
@Value
@Builder
public class AdjustmentRequestResponse {

    @Schema(
            description = "Unique identifier of the created adjustment request",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID adjustmentRequestId;

    @Schema(
            description = "Stock-keeping unit of the product being adjusted",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    String productSku;

    @Schema(
            description = "Identifier of the location where the adjustment applies",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    UUID locationId;

    @Schema(
            description = "Requested adjustment quantity (positive to add, negative to remove)",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    Integer quantity;

    @Schema(
            description = "Reason code explaining why the adjustment was requested",
            example = "CYCLE_COUNT",
            requiredMode = REQUIRED)
    @NotNull
    String reasonCode;

    @Schema(
            description = "Current workflow status of the adjustment request",
            example = "DRAFT",
            requiredMode = REQUIRED)
    @NotNull
    String status;
}
