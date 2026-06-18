package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.shopmanager.service.enums.ConflictReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "A time block during which a mechanic is unavailable, with the reason for the conflict")
public class ConflictBlock {
    @Schema(
            description = "Conflict block start instant in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    Instant startTime;

    @Schema(
            description = "Conflict block end instant in UTC (ISO-8601)",
            example = "2026-06-18T12:00:00Z",
            requiredMode = REQUIRED)
    Instant endTime;

    @Schema(description = "Reason code explaining the conflict", example = "ON_PTO", requiredMode = REQUIRED)
    ConflictReasonCode reasonCode;

    @Schema(
            description = "Optional human-readable detail about the conflict",
            example = "Approved vacation",
            requiredMode = NOT_REQUIRED)
    String detail;
}
