package com.positivity.nhtsa.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle manufacturer reference sourced from the NHTSA dataset")
public class ManufacturerResponse {

    @Schema(
            description = "Internal record identifier for the manufacturer reference entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    UUID id;

    @Schema(description = "Human-readable manufacturer name", example = "Ford Motor Company", requiredMode = REQUIRED)
    @NotNull
    String name;
}
