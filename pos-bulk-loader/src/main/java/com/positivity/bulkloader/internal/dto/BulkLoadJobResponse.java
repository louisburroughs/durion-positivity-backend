package com.positivity.bulkloader.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@Schema(description = "Bulk load job state and progress counters")
public class BulkLoadJobResponse {

    @Schema(
            description = "Unique identifier of the bulk load job",
            example = "00000000-0000-0000-0000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the operator who created the job",
            example = "operator-123",
            requiredMode = NOT_REQUIRED)
    private String operatorId;

    @Schema(
            description = "Identifier of the location the job targets, if scoped to one",
            example = "00000000-0000-0000-0000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Original name of the uploaded source file",
            example = "products-2026-01.csv",
            requiredMode = REQUIRED)
    @NotNull
    private String fileName;

    @Schema(
            description = "Detected or selected target domain for the load",
            example = "CATALOG_PRODUCT",
            requiredMode = REQUIRED)
    @NotNull
    private DomainType domainType;

    @Schema(
            description = "Current lifecycle status of the job",
            example = "PROCESSING",
            requiredMode = REQUIRED)
    @NotNull
    private JobStatus status;

    @Schema(
            description = "Total number of data rows detected in the source file",
            example = "1000",
            requiredMode = NOT_REQUIRED)
    private Long totalRows;

    @Schema(
            description = "Number of rows processed so far",
            example = "750",
            requiredMode = NOT_REQUIRED)
    private Long processedRows;

    @Schema(
            description = "Number of rows successfully loaded",
            example = "740",
            requiredMode = NOT_REQUIRED)
    private Long successCount;

    @Schema(
            description = "Number of rows that failed processing",
            example = "10",
            requiredMode = NOT_REQUIRED)
    private Long failureCount;

    @Schema(
            description = "Timestamp when processing started (ISO 8601)",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant startedAt;

    @Schema(
            description = "Timestamp when processing completed (ISO 8601)",
            example = "2026-01-15T09:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(
            description = "Timestamp when the job was created (ISO 8601)",
            example = "2026-01-15T09:25:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the job was last updated (ISO 8601)",
            example = "2026-01-15T09:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
