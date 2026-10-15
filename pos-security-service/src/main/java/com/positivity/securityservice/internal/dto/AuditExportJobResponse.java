package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportDeliveryStatus;
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
            description = "How the export is delivered: DOWNLOAD (fetch downloadUrl) or WEBHOOK (sent to the"
                    + " tenant's registered webhook, webhookUrl)",
            example = "DOWNLOAD",
            requiredMode = REQUIRED)
    private AuditDeliveryMode deliveryMode;

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
                    + " security:audit:export authority (null until COMPLETED; a WEBHOOK job whose delivery"
                    + " failed is FAILED and keeps no file)",
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

    @Schema(
            description = "WEBHOOK only: the destination this job delivers to, pinned when it was requested (a"
                    + " later re-registration to another URL is never used for it); null for DOWNLOAD",
            example = "https://siem.example.com/hooks/positivity-audit",
            requiredMode = NOT_REQUIRED)
    private String webhookUrl;

    @Schema(
            description = "WEBHOOK only: DELIVERED (an attempt answered 2xx; the job is COMPLETED) or FAILED (no"
                    + " attempt succeeded, or the destination changed or was refused; the job is FAILED); null"
                    + " for DOWNLOAD and until delivery ends",
            example = "DELIVERED",
            requiredMode = NOT_REQUIRED)
    private AuditExportDeliveryStatus deliveryStatus;

    @Schema(
            description = "WEBHOOK only: how many delivery attempts were made; null until delivery ends",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private Integer deliveryAttempts;

    @Schema(
            description = "WEBHOOK only: when the endpoint accepted the file; null unless DELIVERED",
            example = "2026-01-15T09:35:02Z",
            requiredMode = NOT_REQUIRED)
    private Instant deliveredAt;

    @Schema(
            description = "WEBHOOK only: short reason the last attempt failed (an HTTP status such as \"HTTP 503\","
                    + " an exception class name, DESTINATION_CHANGED or DESTINATION_REFUSED), never the endpoint's"
                    + " response body; null unless delivery failed",
            example = "HTTP 503",
            requiredMode = NOT_REQUIRED)
    private String deliveryError;
}
