package com.positivity.workorder.internal.client;

import com.positivity.workorder.internal.dto.OperationalContextResponse;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client for retrieving operational context from the Shop Management Service.
 * CAP:140 Story #59.
 */
@Slf4j
@Component
public class ShopmgrOperationalContextClient {

    private final RestClient restClient;

    public ShopmgrOperationalContextClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.shopmgr.base-url:http://api-gateway}") String shopmgrBaseUrl) {
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
            return restClient
                    .get()
                    .uri("/shop-manager/v1/shopmgr/workorders/{workorderId}/operationalContext", workorderId)
                    .retrieve()
                    .body(OperationalContextResponse.class);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new IllegalStateException("Shopmgr unavailable: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Shopmgr unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the bay availability status for all bays at a given location.
     *
     * @param locationId the location UUID
     * @return list of bay availability DTOs, empty list on 404 or connection error
     */
    public List<BayAvailabilityDto> getBayStatusForLocation(@NonNull UUID locationId) {
        try {
            List<BayAvailabilityDto> result = restClient
                    .get()
                    .uri("/shop-manager/v1/shopmgr/locations/{locationId}/bays", locationId)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<BayAvailabilityDto>>() {});
            return result != null ? result : List.of();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw e;
        } catch (ResourceAccessException e) {
            log.warn("Shopmgr unavailable when fetching bays for location {}: {}", locationId, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("Shopmgr unavailable when fetching bays for location {}: {}", locationId, e.getMessage());
            return List.of();
        }
    }

    /**
     * DTO for bay availability status from the Shop Management Service.
     *
     * @param bayId   unique identifier for the bay
     * @param bayName human-readable bay name
     * @param status  bay status: OPEN, CLOSED, RESERVED, UNDER_MAINTENANCE, etc.
     */
    public record BayAvailabilityDto(UUID bayId, String bayName, String status) {}
}
