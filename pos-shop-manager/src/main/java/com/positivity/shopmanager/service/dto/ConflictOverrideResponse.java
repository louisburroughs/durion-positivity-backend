package com.positivity.shopmanager.service.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Response returned after a successful conflict override. */
@Value
@Builder
public class ConflictOverrideResponse {
    UUID overrideId;
    UUID appointmentId;
    String overriddenByUserId;
    Instant overrideTimestamp;
    String overrideReason;
}
