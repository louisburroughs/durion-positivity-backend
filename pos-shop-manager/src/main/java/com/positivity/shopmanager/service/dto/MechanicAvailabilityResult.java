package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.AvailabilityStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MechanicAvailabilityResult {
    UUID mechanicId;
    String personId;
    AvailabilityStatus overallStatus;
    Instant windowStart;
    Instant windowEnd;
    List<ConflictBlock> conflicts;
}
