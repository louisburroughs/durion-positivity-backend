package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Inferred or user-overridden mapping from a source column to a target field")
public class ColumnMappingResponse {

    @Schema(
            description = "Unique identifier of the column mapping",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the bulk load job this mapping belongs to",
            example = "00000000-0000-0000-0000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID jobId;

    @Schema(
            description = "Name of the column in the source file",
            example = "Product SKU",
            requiredMode = REQUIRED)
    @NotNull
    private String sourceColumn;

    @Schema(
            description = "Name of the target domain field the column maps to",
            example = "sku",
            requiredMode = REQUIRED)
    @NotNull
    private String targetField;

    @Schema(
            description = "Confidence score of the inferred mapping, between 0 and 1",
            example = "0.95",
            requiredMode = NOT_REQUIRED)
    private Double confidence;

    @Schema(
            description = "Whether the mapping was overridden by a user",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean overriddenByUser;

    @Schema(
            description = "Timestamp when the mapping was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;
}
