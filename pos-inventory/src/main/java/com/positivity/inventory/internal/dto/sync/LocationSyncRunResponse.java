package com.positivity.inventory.internal.dto.sync;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Summary of one location sync run triggered against pos-location
 * (CAP-214 #40).
 */
@Schema(description = "Summary of one location sync run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocationSyncRunResponse {

    @Schema(
            description = "Identifier of the sync run",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID syncRunId;

    @Schema(description = "Overall outcome of the run", example = "OK", requiredMode = REQUIRED)
    private String outcome;

    @Schema(description = "Number of roster records processed", example = "42", requiredMode = REQUIRED)
    private int locationsProcessed;

    @Schema(description = "Number of location refs created", example = "3", requiredMode = REQUIRED)
    private int locationsCreated;

    @Schema(description = "Number of location refs updated", example = "5", requiredMode = REQUIRED)
    private int locationsUpdated;

    @Schema(description = "Number of roster records already up to date", example = "34", requiredMode = REQUIRED)
    private int locationsUnchanged;

    @Schema(description = "Number of roster records that failed to apply", example = "0", requiredMode = REQUIRED)
    private int locationsFailed;

    @Schema(description = "When the run started", requiredMode = NOT_REQUIRED)
    private Instant startedAt;

    @Schema(description = "When the run completed", requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(description = "Correlation id of the triggering request", requiredMode = NOT_REQUIRED)
    private String correlationId;
}
