package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.AvailabilityStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Result of evaluating a mechanic's availability over a time window")
public class MechanicAvailabilityResult {
    @Schema(
            description = "Mechanic identifier",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    UUID mechanicId;

    @Schema(
            description = "Person identifier of the mechanic",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    String personId;

    @Schema(
            description = "Overall availability status for the requested window",
            example = "AVAILABLE",
            requiredMode = REQUIRED)
    AvailabilityStatus overallStatus;

    @Schema(
            description = "Start of the evaluated window in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    Instant windowStart;

    @Schema(
            description = "End of the evaluated window in UTC (ISO-8601)",
            example = "2026-06-18T17:00:00Z",
            requiredMode = REQUIRED)
    Instant windowEnd;

    @Schema(description = "Conflict blocks within the window, if any", requiredMode = NOT_REQUIRED)
    List<ConflictBlock> conflicts;
}
