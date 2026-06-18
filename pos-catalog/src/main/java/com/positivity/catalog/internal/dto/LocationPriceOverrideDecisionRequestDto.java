package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Request payload for approving or rejecting a location price override")
public class LocationPriceOverrideDecisionRequestDto {

    @NotNull
    @Schema(description = "Expected version for optimistic locking", example = "1", requiredMode = REQUIRED)
    private Long version;

    @NotNull
    @Schema(
            description = "Identifier of the user making the decision",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = REQUIRED)
    private UUID actorUserId;

    @Schema(description = "Reason code when rejecting the override", example = "BELOW_MARGIN", requiredMode = NOT_REQUIRED)
    private String rejectionReasonCode;

    @Schema(
            description = "Free-text notes when rejecting the override",
            example = "Margin too low for this location",
            requiredMode = NOT_REQUIRED)
    private String rejectionNotes;
}
