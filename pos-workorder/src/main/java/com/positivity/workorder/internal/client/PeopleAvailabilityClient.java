package com.positivity.workorder.internal.client;

import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

/**
 * Client for fetching mechanic availability from the People service.
 * Calls GET /v1/people/availability to retrieve real-time roster data.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PeopleAvailabilityClient {

    private final RestClient peopleServiceRestClient;

    /**
     * Fetches mechanic availability for a given location and date.
     * Returns real-time clock status, break state, PTO, and schedule for each mechanic.
     */
    public PeopleAvailabilityResponse fetchAvailability(@NonNull String locationId, @NonNull LocalDate date) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
