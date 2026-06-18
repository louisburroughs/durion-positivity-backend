package com.positivity.people.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Result of validating a location reference against the location service")
public record LocationValidationResponse(
        @Schema(
                        description = "Location identifier that was validated",
                        example = "01960011-0000-7000-8000-000000000001",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                UUID locationId,
        @Schema(description = "Whether a location with this identifier exists", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean exists,
        @Schema(description = "Whether the referenced location is active", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
                boolean active) {}
