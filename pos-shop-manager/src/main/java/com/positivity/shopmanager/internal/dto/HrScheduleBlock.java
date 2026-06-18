package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * A single block returned from the HR availability system —
 * either a scheduled shift or a PTO/time-off block.
 */
@Value
@Builder
@Schema(description = "A single availability block from the HR system (a scheduled shift or a PTO/time-off block)")
public class HrScheduleBlock {
    /** "SHIFT" or "PTO" */
    @Schema(description = "Block type (SHIFT or PTO)", example = "SHIFT", requiredMode = REQUIRED)
    String blockType;

    @Schema(
            description = "Block start instant in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    Instant startTime;

    @Schema(
            description = "Block end instant in UTC (ISO-8601)",
            example = "2026-06-18T17:00:00Z",
            requiredMode = REQUIRED)
    Instant endTime;
}
