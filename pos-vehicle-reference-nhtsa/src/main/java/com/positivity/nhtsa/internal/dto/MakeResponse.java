package com.positivity.nhtsa.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle make reference sourced from the NHTSA dataset")
public class MakeResponse {

    @Schema(
            description = "Internal record identifier for the make reference entry",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    @NotNull
    UUID id;

    @Schema(description = "Human-readable make name", example = "Ford", requiredMode = REQUIRED)
    @NotNull
    String name;

    @Schema(
            description = "Identifiers of every manufacturer this make is linked to, sorted. A make is a brand and one"
                    + " vPIC make can be built by several manufacturers, so it appears under each of them with this"
                    + " same id.",
            example = "[\"550e8400-e29b-41d4-a716-446655440001\"]",
            requiredMode = REQUIRED)
    @NotNull
    List<UUID> manufacturerIds;
}
