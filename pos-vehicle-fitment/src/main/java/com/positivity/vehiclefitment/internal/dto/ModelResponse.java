package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle model response")
public class ModelResponse {
    @Schema(
            description = "Model identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440020")
    UUID id;

    @Schema(description = "Model name", requiredMode = Schema.RequiredMode.REQUIRED, example = "Camry SE")
    String name;

    @Schema(description = "Make identifier for this model", example = "550e8400-e29b-41d4-a716-446655440010")
    UUID makeId;
}
