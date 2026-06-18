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
@Schema(description = "A single entry in an accounting event's processing audit log")
public class EventProcessingLogEntry {

    @Schema(description = "Unique identifier for this log entry", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID entryId;

    @Schema(description = "When this log entry was created (ISO 8601)", example = "2026-06-18T08:00:00Z", requiredMode = REQUIRED)
    private Instant occurredAt;

    @Schema(description = "Severity level: INFO, WARN, or ERROR", example = "INFO", requiredMode = REQUIRED)
    private String severity;

    @Schema(description = "Human-readable log message", example = "Event processed successfully", requiredMode = REQUIRED)
    private String message;

    @Nullable
    @Schema(description = "Optional JSON context for debugging", example = "{\"retries\":0}", nullable = true, requiredMode = NOT_REQUIRED)
    private String contextJson;
}
