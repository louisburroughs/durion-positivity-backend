package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Product replacement option request")
public class ProductReplacementRequest {
    @Schema(description = "Replacement product identifier", example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d")
    private UUID replacementProductId;

    @Schema(description = "Display priority order", example = "1")
    private Integer priorityOrder;

    @Schema(description = "Replacement notes")
    private String notes;

    @Schema(description = "Replacement effective instant")
    private Instant effectiveAt;
}
