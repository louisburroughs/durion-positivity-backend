package com.positivity.shopmanager.internal.client;

import com.positivity.shopmanager.internal.dto.HrScheduleBlock;
import com.positivity.shopmanager.internal.exception.HrUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HrAvailabilityClient {

    private final RestClient hrRestClient;

    public HrAvailabilityClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder builder,
            @Value("${pos.hr.base-url:http://api-gateway}") String hrBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(200));
        requestFactory.setReadTimeout(Duration.ofMillis(200));
        this.hrRestClient = builder.baseUrl(hrBaseUrl).requestFactory(requestFactory).build();
    }

    public Object getAvailabilityOverlay(String locationId, LocalDate date) {
        return hrRestClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/people/v1/availability/overlay")
                        .queryParam("locationId", locationId)
                        .queryParam("date", date)
                        .build())
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
                            .path("/people/hr/v1/schedules")
                            .queryParam("personId", personId)
                            .queryParam("startTime", windowStart.toString())
                            .queryParam("endTime", windowEnd.toString())
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<HrScheduleBlock>>() {
                    });
            return result != null ? result : List.of();
        } catch (Exception e) {
            throw new HrUnavailableException("HR system is unavailable: " + e.getMessage(), e);
        }
    }
}
