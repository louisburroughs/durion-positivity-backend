package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Resolved workorder id to human workorder number pairing.
 */
@Schema(description = "Workorder id resolved to its human workorder number")
public record WorkorderNumberRef(
        @Schema(
                        description = "Workorder identifier",
                        example = "550e8400-e29b-41d4-a716-446655440000",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID workorderId,
        @Schema(description = "Human workorder number", example = "WO-2026-1001", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
                String workorderNumber) {}
