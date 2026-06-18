package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.ReviewStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Audit record for a single row processed during a bulk load job")
public class AuditRecordResponse {

    @Schema(
            description = "Unique identifier of the audit record",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the bulk load job this audit record belongs to",
            example = "00000000-0000-0000-0000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID jobId;

    @Schema(
            description = "Type of the target entity for the processed row",
            example = "CATALOG_PRODUCT",
            requiredMode = NOT_REQUIRED)
    private String entityType;

    @Schema(
            description = "Identifier of the entity created or updated from the row, if any",
            example = "00000000-0000-0000-0000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID entityId;

    @Schema(
            description = "One-based row number within the source file",
            example = "42",
            requiredMode = NOT_REQUIRED)
    private Long rowNumber;

    @Schema(
            description = "Review status of the audit record",
            example = "PENDING",
            requiredMode = REQUIRED)
    @NotNull
    private ReviewStatus reviewStatus;

    @Schema(
            description = "Machine-readable reason codes describing why the row needs review",
            example = "MISSING_REQUIRED_FIELD,INVALID_PRICE",
            requiredMode = NOT_REQUIRED)
    private String reasonCodes;

    @Schema(
            description = "Serialized original values from the source row",
            example = "{\"sku\": \"PROD-001\", \"price\": \"\"}",
            requiredMode = NOT_REQUIRED)
    private String originalValues;

    @Schema(
            description = "Timestamp when the audit record was created (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;
}
