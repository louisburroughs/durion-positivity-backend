package com.positivity.shopmanager.service.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/**
 * Request payload for assigning mechanics and an optional resource to an
 * appointment.
 */
@Value
@Builder
public class CreateAssignmentRequest {
    @NonNull
    UUID appointmentId;
    /** At least one entry with role LEAD is required (AC-4). */
    @NonNull
    List<MechanicAssignmentItem> mechanics;
    /** Optional — bay or mobile unit to associate with this assignment. */
    UUID resourceId;

    String resourceType;
    /** When true the caller is asserting override for detected conflicts (AC-5). */
    boolean override;

    String overrideReason;
}
