package com.positivity.people.service;

import com.positivity.people.internal.dto.PeopleAvailabilityResponse;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PeopleAvailabilityService {

    @NonNull
    List<PeopleAvailabilityResponse> getPeopleAvailability(UUID locationId, LocalDate date);
}
