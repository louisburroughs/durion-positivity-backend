package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle make response")
public class MakeResponse {
    @Schema(
            description = "Make identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440010")
    UUID id;

    @Schema(description = "Make name", requiredMode = Schema.RequiredMode.REQUIRED, example = "Camry")
    String name;

    @Schema(
            description = "Identifiers of every manufacturer this make is linked to, sorted. A make is a brand and one"
                    + " vPIC make can be built by several manufacturers, so it appears under each of them"
                    + " with this same id.",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "[\"550e8400-e29b-41d4-a716-446655440001\"]")
    List<UUID> manufacturerIds;
}
