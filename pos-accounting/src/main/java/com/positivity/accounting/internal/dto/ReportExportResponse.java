package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/**
 * Response representing the state of an asynchronous report export job.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Async report export job state")
public class ReportExportResponse {

    @Schema(
            description = "Unique export job identifier",
            example = "01936e5c-7890-7a3d-8b6e-2b3456789012",
            requiredMode = REQUIRED)
    @NotNull
    private UUID exportId;

    @Schema(description = "Current export status", example = "PENDING", requiredMode = REQUIRED)
    @NotNull
    private ExportStatus status;

    @Schema(
            description = "ISO-8601 timestamp when the export was requested",
            example = "2026-04-25T10:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant requestedAt;

    /**
     * ISO-8601 timestamp when the export completed or failed.
     * Null while status is PENDING or IN_PROGRESS.
     */
    @Schema(
            description = "ISO-8601 timestamp when export completed (null if not finished)",
            example = "2026-04-25T10:00:05Z",
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    /**
     * Pre-signed or endpoint URL for downloading the export artifact.
     * Only set when status is COMPLETED.
     */
    @Schema(
            description = "Download URL (only present when status is COMPLETED)",
            example = "https://storage.example.com/exports/export-abc.csv",
            requiredMode = NOT_REQUIRED)
    private String downloadUrl;

    @Schema(description = "Export format", example = "CSV", requiredMode = NOT_REQUIRED)
    private ExportFormat format;

    @Schema(description = "Report type that was exported", example = "JOURNAL_LINES", requiredMode = NOT_REQUIRED)
    private String reportType;
}
