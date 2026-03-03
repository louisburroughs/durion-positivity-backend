package com.positivity.workorder.internal.client;

import com.positivity.workorder.internal.dto.OperationalContextResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * Client for retrieving operational context from the Shop Management Service.
 * CAP:140 Story #59.
 */
@Slf4j
@Component
public class ShopmgrOperationalContextClient {

    private final RestClient restClient;

    public ShopmgrOperationalContextClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.shopmgr.base-url:http://localhost:8080}") String shopmgrBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(shopmgrBaseUrl).build();
    }

    /**
     * Fetches the operational context for a given workorder from Shopmgr.
     *
     * @param workorderId the workorder ID
     * @return the operational context
     */
    public OperationalContextResponse getOperationalContext(@NonNull UUID workorderId) {
        try {
            return restClient.get()
                    .uri("/v1/shopmgr/workorders/{workorderId}/operationalContext", workorderId)
                    .retrieve()
                    .body(OperationalContextResponse.class);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Shopmgr unavailable: " + e.getMessage(), e);
        }
    }
}