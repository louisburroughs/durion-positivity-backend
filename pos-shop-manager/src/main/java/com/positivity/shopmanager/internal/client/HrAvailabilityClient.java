package com.positivity.shopmanager.internal.client;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class HrAvailabilityClient {

    private final RestClient hrRestClient;

    public HrAvailabilityClient(
            RestClient.Builder builder,
            @Value("${pos.hr.base-url:http://localhost:8086}") String hrBaseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(200));
        requestFactory.setReadTimeout(Duration.ofMillis(200));
        this.hrRestClient = builder
                .baseUrl(hrBaseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public Object getAvailabilityOverlay(String locationId, LocalDate date) {
        return hrRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/people/v1/availability/overlay")
                        .queryParam("locationId", locationId)
                        .queryParam("date", date)
                        .build())
                .retrieve()
                .body(Object.class);
    }
}
