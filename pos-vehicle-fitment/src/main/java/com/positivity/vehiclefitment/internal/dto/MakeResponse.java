package com.positivity.vehiclefitment.internal.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle make response")
public class MakeResponse {
    @Schema(description = "Make identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "550e8400-e29b-41d4-a716-446655440010")
    UUID id;
    @Schema(description = "Make name", requiredMode = Schema.RequiredMode.REQUIRED, example = "Camry")
    String name;
    @Schema(description = "Manufacturer identifier for this make", example = "550e8400-e29b-41d4-a716-446655440001")
    UUID manufacturerId;
}
