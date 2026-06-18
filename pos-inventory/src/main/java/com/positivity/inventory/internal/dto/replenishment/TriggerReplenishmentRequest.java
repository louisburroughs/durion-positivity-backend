package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to trigger replenishment for a product at a pick-face location")
public class TriggerReplenishmentRequest {

    @Schema(
            description = "Identifier of the product to replenish",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    @NotBlank
    private String productId;

    @Schema(
            description = "Identifier of the pick-face location to replenish stock into",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID pickFaceLocationId;
}
