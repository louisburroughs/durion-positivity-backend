package com.positivity.accounting.internal.dto;

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

  @Schema(description = "Unique job identifier")
  private UUID jobId;

  @Schema(description = "Current job status")
  private String status;

  @Schema(description = "When the export was requested")
  private Instant requestedAt;

  @Nullable
  @Schema(description = "When the export completed", nullable = true)
  private Instant completedAt;

  @Nullable
  @Schema(description = "Download URL when available", nullable = true)
  private String downloadUrl;

  @Nullable
  @Schema(description = "Error message if job failed", nullable = true)
  private String errorMessage;
}
