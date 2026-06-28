package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Status and metadata for an export job")
public class ExportJobResponse {

    @Schema(
            description = "Unique job identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID jobId;

    @Schema(description = "Current job status", example = "COMPLETED", requiredMode = REQUIRED)
    private String status;

    @Schema(
            description = "When the export was requested (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant requestedAt;

    @Nullable
    @Schema(
            description = "When the export completed (ISO 8601)",
            example = "2026-06-18T08:05:00Z",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Nullable
    @Schema(
            description = "Download URL when available",
            example = "https://exports.example.com/jobs/abc123.csv",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String downloadUrl;

    @Nullable
    @Schema(
            description = "Error message if job failed",
            example = "Source dataset unavailable",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private String errorMessage;
}
