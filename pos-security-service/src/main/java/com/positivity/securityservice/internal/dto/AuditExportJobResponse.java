package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for audit export job operations (B-4).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Audit export job status response")
public class AuditExportJobResponse {

    @Schema(description = "Export job UUID", example = "01960000-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID jobId;

    @Schema(
            description = "Current status of the export job: PENDING (queued), IN_PROGRESS (running), COMPLETED"
                    + " (file ready to download) or FAILED (see errorMessage)",
            example = "COMPLETED",
            requiredMode = REQUIRED)
    private AuditExportStatus status;

    @Schema(description = "Output format of the export file", example = "CSV", requiredMode = REQUIRED)
    private AuditExportFormat format;

    @Schema(
            description = "Timestamp when the export was requested",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant requestedAt;

    @Schema(
            description = "Timestamp when the export completed (null if not yet complete)",
            example = "2026-01-15T09:35:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(
            description = "Gateway-relative path that downloads the export file with the same"
                    + " security:audit:export authority (null until COMPLETED)",
            example = "/security-service/v1/audit/exports/01960000-0000-7000-8000-000000000001/download",
            requiredMode = NOT_REQUIRED)
    private String downloadUrl;

    @Schema(
            description = "Number of audit events in the export file (null until COMPLETED)",
            example = "1250",
            requiredMode = NOT_REQUIRED)
    private Long rowCount;

    @Schema(
            description = "Error message when status is FAILED",
            example =
                    "The export matches 250000 audit events, more than the limit of 100000; narrow the filters (for example the date range) and request it again.",
            requiredMode = NOT_REQUIRED)
    private String errorMessage;
}
