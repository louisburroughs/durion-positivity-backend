package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Unique export job identifier", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
    private UUID exportId;

    @Schema(description = "Current export status", example = "PENDING")
    private ExportStatus status;

    @Schema(description = "ISO-8601 timestamp when the export was requested", example = "2026-04-25T10:00:00Z")
    private Instant requestedAt;

    /**
     * ISO-8601 timestamp when the export completed or failed.
     * Null while status is PENDING or IN_PROGRESS.
     */
    @Schema(
            description = "ISO-8601 timestamp when export completed (null if not finished)",
            example = "2026-04-25T10:00:05Z")
    private Instant completedAt;

    /**
     * Pre-signed or endpoint URL for downloading the export artifact.
     * Only set when status is COMPLETED.
     */
    @Schema(
            description = "Download URL (only present when status is COMPLETED)",
            example = "https://storage.example.com/exports/export-abc.csv")
    private String downloadUrl;

    @Schema(description = "Export format", example = "CSV")
    private ExportFormat format;

    @Schema(description = "Report type that was exported", example = "JOURNAL_LINES")
    private String reportType;
}
