package com.positivity.shopmanager.service.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/** Request payload for bypassing a scheduling conflict with manager permission. */
@Value
@Builder
public class ConflictOverrideRequest {
    @NonNull
    UUID appointmentId;
    /** Non-blank reason for the override, e.g. "Customer waiting on-site". */
    @NonNull
    String overrideReason;
    /** JSON string describing the conflict, e.g. {"type":"TECHNICIAN_DOUBLE_BOOKED","resourceId":"..."}. */
    String conflictDetails;
}
