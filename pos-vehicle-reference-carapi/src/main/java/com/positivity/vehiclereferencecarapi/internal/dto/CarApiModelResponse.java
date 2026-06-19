package com.positivity.vehiclereferencecarapi.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle model reference sourced from the CarAPI provider")
public class CarApiModelResponse {

    @Schema(
            description = "Internal record identifier for the model reference entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    UUID id;

    @Schema(
            description = "Provider model identifier from CarAPI",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = REQUIRED)
    @NotNull
    UUID modelId;

    @Schema(
            description = "Provider make identifier the model belongs to",
            example = "550e8400-e29b-41d4-a716-446655440002",
            requiredMode = REQUIRED)
    @NotNull
    UUID makeId;

    @Schema(description = "Human-readable model name", example = "F-150", requiredMode = REQUIRED)
    @NotNull
    String modelName;
}
