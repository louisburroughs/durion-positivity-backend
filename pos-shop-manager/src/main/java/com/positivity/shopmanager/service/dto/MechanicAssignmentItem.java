package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.MechanicRole;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/**
 * A mechanic entry inside a CreateAssignmentRequest.
 * {@code role} is optional; when null the service defaults it to
 * {@link MechanicRole#LEAD}
 * for single-mechanic assignments.
 */
@Value
@Builder
public class MechanicAssignmentItem {
    @NonNull
    String mechanicPersonId;
    /** May be null; single-mechanic assignments default to LEAD. */
    MechanicRole role;
}
