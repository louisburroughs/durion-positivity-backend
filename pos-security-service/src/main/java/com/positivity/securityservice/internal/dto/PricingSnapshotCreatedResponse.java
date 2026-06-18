package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Minimal create response for pricing snapshot creation.
 *
 * Issue: #41
 */
@Value
@Builder
@Schema(description = "Minimal acknowledgement returned after creating a pricing snapshot")
public class PricingSnapshotCreatedResponse {
    @Schema(
            description = "Identifier of the created pricing snapshot",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    UUID snapshotId;
}
