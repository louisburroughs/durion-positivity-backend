package com.positivity.securityservice.internal.dto;

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

  @Schema(description = "Export job UUID", example = "550e8400-e29b-41d4-a716-446655440000")
  private UUID jobId;

  @Schema(description = "Current status of the export job")
  private AuditExportStatus status;

  @Schema(description = "Timestamp when the export was requested")
  private Instant requestedAt;

  @Schema(description = "Timestamp when the export completed (null if not yet complete)")
  private Instant completedAt;

  @Schema(description = "Pre-signed download URL (null until COMPLETED)")
  private String downloadUrl;

  @Schema(description = "Error message when status is FAILED")
  private String errorMessage;
}
