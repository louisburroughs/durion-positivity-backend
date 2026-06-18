package com.positivity.people.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Minimal location payload returned by the location service.
 *
 * Issue: #79
 */
@Schema(description = "Minimal location summary returned by the location service")
public record LocationSummaryResponse(
        @Schema(
                        description = "Location identifier",
                        example = "01960011-0000-7000-8000-000000000001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID id,
        @Schema(description = "Location display name", example = "Downtown Service Center", requiredMode = Schema.RequiredMode.REQUIRED)
                String name,
        @Schema(description = "Whether the location is active", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean active) {}
