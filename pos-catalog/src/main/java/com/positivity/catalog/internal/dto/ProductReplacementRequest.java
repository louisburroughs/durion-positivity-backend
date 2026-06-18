package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Product replacement option request")
public class ProductReplacementRequest {
    @NotNull
    @Schema(
            description = "Replacement product identifier",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID replacementProductId;

    @Schema(description = "Display priority order", example = "1", requiredMode = NOT_REQUIRED)
    private Integer priorityOrder;

    @Schema(description = "Replacement notes", example = "Direct successor model", requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(description = "Replacement effective instant", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant effectiveAt;
}
