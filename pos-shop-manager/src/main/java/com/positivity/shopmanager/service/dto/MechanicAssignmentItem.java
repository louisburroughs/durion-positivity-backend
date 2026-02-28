package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.MechanicRole;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;

/** A mechanic entry inside a CreateAssignmentRequest. */
@Value
@Builder
public class MechanicAssignmentItem {
    @NonNull String mechanicPersonId;
    @NonNull MechanicRole role;
}
