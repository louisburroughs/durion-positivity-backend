package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.HrScheduleBlock;
import com.positivity.shopmanager.internal.exception.HrUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HrAvailabilityClient {

    private final RestClient hrRestClient;

    public HrAvailabilityClient(@Qualifier("hrRestClient") RestClient hrRestClient) {
        this.hrRestClient = hrRestClient;
    }

    public Object getAvailabilityOverlay(String locationId, LocalDate date) {
        return hrRestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/people/availability")
                        .queryParam("locationId", locationId)
                        .queryParam("date", date)
                        .build())
                .header("X-User", "pos-shop-manager")
                .header("X-Authorities", "people:availability:view")
                .retrieve()
                .body(Object.class);
    }

    /**
     * Retrieve shift and PTO blocks for a mechanic from the HR system for the
     * given time window. Returns an empty list when the person has no entries.
     *
     * @throws HrUnavailableException when the HR system cannot be reached or
     *                                returns an error
     */
    public List<HrScheduleBlock> getScheduleBlocks(String personId, Instant windowStart, Instant windowEnd) {
        try {
            List<HrScheduleBlock> result = hrRestClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/hr/v1/schedules")
                            .queryParam("personId", personId)
                            .queryParam("startTime", windowStart.toString())
                            .queryParam("endTime", windowEnd.toString())
                            .build())
                    .header("X-User", "pos-shop-manager")
                    .header("X-Authorities", "people:availability:view")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<HrScheduleBlock>>() {});
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new HrUnavailableException("HR system is unavailable: " + e.getMessage(), e);
        }
    }
}
