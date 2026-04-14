package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle manufacturer response")
public class ManufacturerResponse {
    @Schema(
            description = "Manufacturer identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440001")
    UUID id;

    @Schema(description = "Manufacturer name", requiredMode = Schema.RequiredMode.REQUIRED, example = "Toyota")
    String name;
}
