package com.positivity.location.internal.dto;

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
@Schema(description = "Validation result for a location lookup")
public class LocationValidationResponseDTO {

    @Schema(
            description = "Identifier of the location that was validated",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Whether the location exists", example = "true", requiredMode = REQUIRED)
    private boolean exists;

    @Schema(description = "Whether the location is active", example = "true", requiredMode = REQUIRED)
    private boolean active;
}
