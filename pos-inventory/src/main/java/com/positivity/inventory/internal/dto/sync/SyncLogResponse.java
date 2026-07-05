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
 * One location sync audit entry (CAP-214 #40): either a run-level summary
 * or a record-level failure that shares the run's {@code syncRunId}.
 */
@Schema(description = "Location sync audit log entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncLogResponse {

    @Schema(
            description = "Identifier of this log entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID syncLogId;

    @Schema(
            description = "Identifier of the sync run this entry belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private UUID syncRunId;

    @Schema(description = "RUN summary entry or RECORD failure entry", example = "RUN", requiredMode = REQUIRED)
    private String scope;

    @Schema(description = "Outcome of the run or record", example = "OK", requiredMode = REQUIRED)
    private String outcome;

    @Schema(description = "Correlation id of the triggering request", requiredMode = NOT_REQUIRED)
    private String correlationId;

    @Schema(description = "User or system that triggered the run", requiredMode = NOT_REQUIRED)
    private String triggeredBy;

    @Schema(description = "pos-location id of the record (RECORD scope only)", requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "HR location id of the record (RECORD scope only)", requiredMode = NOT_REQUIRED)
    private String hrLocationId;

    @Schema(description = "Raw roster payload of a failed record", requiredMode = NOT_REQUIRED)
    private String payload;

    @Schema(description = "Error detail when the entry is a failure", requiredMode = NOT_REQUIRED)
    private String errorMessage;

    @Schema(description = "Number of roster records processed (RUN scope only)", requiredMode = NOT_REQUIRED)
    private Integer locationsProcessed;

    @Schema(description = "Number of location refs created (RUN scope only)", requiredMode = NOT_REQUIRED)
    private Integer locationsCreated;

    @Schema(description = "Number of location refs updated (RUN scope only)", requiredMode = NOT_REQUIRED)
    private Integer locationsUpdated;

    @Schema(description = "Number of records already up to date (RUN scope only)", requiredMode = NOT_REQUIRED)
    private Integer locationsUnchanged;

    @Schema(description = "Number of records that failed to apply (RUN scope only)", requiredMode = NOT_REQUIRED)
    private Integer locationsFailed;

    @Schema(description = "When the run started", requiredMode = NOT_REQUIRED)
    private Instant startedAt;

    @Schema(description = "When the run completed", requiredMode = NOT_REQUIRED)
    private Instant completedAt;

    @Schema(description = "When this entry was recorded", requiredMode = REQUIRED)
    private Instant createdAt;
}
