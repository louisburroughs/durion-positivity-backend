package com.positivity.people.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PeopleAvailabilityService {

    @NonNull
    List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date);

    /**
     * Resolve the current authenticated user's primary location ID. Uses the security
     * context to identify the user, translates to personId, and returns their primary
     * active staffing location.
     * @return primary location UUID for the current user
     * @throws jakarta.persistence.EntityNotFoundException if user context is missing or
     * no active assignment exists
     */
    @NonNull
    UUID resolveCurrentUserPrimaryLocationId();

    /**
     * Resolve a person's primary location ID from their active staffing assignments.
     * @param personId person to resolve
     * @return primary location UUID for the person
     * @throws jakarta.persistence.EntityNotFoundException if no active assignment is
     * flagged primary
     */
    @NonNull
    UUID resolvePrimaryLocationId(@NonNull UUID personId);
}
