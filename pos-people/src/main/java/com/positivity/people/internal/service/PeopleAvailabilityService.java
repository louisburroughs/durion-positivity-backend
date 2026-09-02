package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import com.positivity.people.internal.dto.PrimaryLocationResolution;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PeopleAvailabilityService {

    @NonNull
    List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date);

    /**
     * Resolve the current authenticated user's primary location. Uses the security
     * context to identify the user, translates to personId, and returns their primary
     * active staffing location. When the user has no person link or no active primary
     * assignment, falls back to the platform's top-level location (resolved from the
     * location service) with {@code defaulted=true}.
     *
     * Issue: #1636
     *
     * @return primary location resolution for the current user
     * @throws jakarta.persistence.EntityNotFoundException if the user context is missing,
     * or no primary assignment exists and no top-level default location could be resolved
     */
    @NonNull
    PrimaryLocationResolution resolveCurrentUserPrimaryLocation();

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
