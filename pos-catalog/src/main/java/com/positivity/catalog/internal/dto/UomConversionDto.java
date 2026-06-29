package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@Schema(description = "A unit-of-measure conversion between two UOM codes")
public class UomConversionDto {
    @NotNull
    @Schema(
            description = "Identifier of the conversion",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @NotNull
    @Schema(description = "Source unit-of-measure code", example = "CASE", requiredMode = REQUIRED)
    private String fromUomCode;

    @NotNull
    @Schema(description = "Target unit-of-measure code", example = "EACH", requiredMode = REQUIRED)
    private String toUomCode;

    @NotNull
    @Schema(
            description = "Multiplier to convert one unit of the source UOM into the target UOM",
            example = "12",
            requiredMode = REQUIRED)
    private BigDecimal conversionFactor;

    @Schema(description = "Whether this conversion is currently active", example = "true", requiredMode = REQUIRED)
    private boolean active;

    @NotNull
    @Schema(
            description = "Timestamp the conversion was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp the conversion was last updated",
            example = "2026-01-16T11:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Identifier of the user who created the conversion",
            example = "system",
            requiredMode = NOT_REQUIRED)
    private String createdBy;
}
