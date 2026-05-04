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
@Schema(description = "A single entry in an accounting event's processing audit log")
public class EventProcessingLogEntry {

  @Schema(description = "Unique identifier for this log entry")
  private UUID entryId;

  @Schema(description = "When this log entry was created")
  private Instant occurredAt;

  @Schema(description = "Severity level: INFO, WARN, or ERROR")
  private String severity;

  @Schema(description = "Human-readable log message")
  private String message;

  @Nullable
  @Schema(description = "Optional JSON context for debugging", nullable = true)
  private String contextJson;
}
