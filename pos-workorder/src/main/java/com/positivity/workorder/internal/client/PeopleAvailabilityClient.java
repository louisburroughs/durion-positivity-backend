package com.positivity.workorder.internal.client;

import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for fetching mechanic availability from the People service.
 * Calls GET /v1/people/availability to retrieve real-time roster data.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PeopleAvailabilityClient {

    private final Clock clock;
    private final RestClient peopleServiceRestClient;

    /**
     * Fetches mechanic availability for a given location and date.
     * Returns real-time clock status, break state, PTO, and schedule for each
     * mechanic.
     */
    public PeopleAvailabilityResponse fetchAvailability(@NonNull String locationId, @NonNull LocalDate date) {
        try {
            return peopleServiceRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/people/availability")
                            .queryParam("locationId", locationId)
                            .queryParam("date", date.toString())
                            .queryParam("includeSchedule", "true")
                            .build())
                    .retrieve()
                    .body(PeopleAvailabilityResponse.class);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            log.warn("No availability data found for locationId={} date={}", locationId, date);
            return PeopleAvailabilityResponse.builder()
                    .asOf(java.time.Instant.now(clock))
                    .location(locationId)
                    .people(java.util.List.of())
                    .build();
        } catch (RestClientException e) {
            log.error("Failed to fetch people availability for locationId={} date={}", locationId, date, e);
            throw new RestClientException(
                    "Failed to fetch people availability for locationId=" + locationId + " date=" + date, e);
        }
    }
}
