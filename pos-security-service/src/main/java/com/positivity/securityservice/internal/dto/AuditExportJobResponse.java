package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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

    @Schema(description = "Export job UUID", example = "550e8400-e29b-41d4-a716-446655440000", requiredMode = REQUIRED)
    private UUID jobId;

    @Schema(description = "Current status of the export job", example = "IN_PROGRESS", requiredMode = REQUIRED)
    private AuditExportStatus status;

    @Schema(description = "Timestamp when the export was requested", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant requestedAt;

    @Schema(
            description = "Timestamp when the export completed (null if not yet complete)",
            example = "2026-01-15T09:35:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(
            description = "Pre-signed download URL (null until COMPLETED)",
            example = "https://exports.example.com/audit/550e8400.csv?sig=...",
            requiredMode = NOT_REQUIRED)
    private String downloadUrl;

    @Schema(
            description = "Error message when status is FAILED",
            example = "Export source unavailable",
            requiredMode = NOT_REQUIRED)
    private String errorMessage;
}
