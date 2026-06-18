package com.positivity.vehiclereferencecarapi.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle make reference sourced from the CarAPI provider")
public class CarApiMakeResponse {

    @Schema(
            description = "Internal record identifier for the make reference entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    UUID id;

    @Schema(
            description = "Provider make identifier from CarAPI",
            example = "550e8400-e29b-41d4-a716-446655440001",
            requiredMode = REQUIRED)
    @NotNull
    UUID makeId;

    @Schema(description = "Human-readable make name", example = "Ford", requiredMode = REQUIRED)
    @NotNull
    String makeName;
}
