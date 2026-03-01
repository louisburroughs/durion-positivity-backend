package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * A single block returned from the HR availability system —
 * either a scheduled shift or a PTO/time-off block.
 */
@Value
@Builder
public class HrScheduleBlock {
    /** "SHIFT" or "PTO" */
    String blockType;
    Instant startTime;
    Instant endTime;
}
