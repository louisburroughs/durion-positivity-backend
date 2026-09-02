package com.positivity.people.internal.client;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of {@link LocationClient}. Calls
 * {@code GET /v1/locations/top-level} on the pos-location service via the load-balanced
 * (Eureka) client, supplying the X-User/X-Authorities headers required for
 * service-to-service calls (the endpoint enforces {@code location:read}).
 *
 * <p>All failures — 404 (no active location), the service being down, or a malformed
 * body — degrade to {@link Optional#empty()} so primary-location resolution can fall back
 * to its own 404 contract instead of surfacing a 5xx.
 *
 * Issue: #1636
 */
@Slf4j
@Component
public class LocationClientImpl implements LocationClient {

    private final RestClient restClient;
    private final String basePath;

    public LocationClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.service-id:location}") String serviceId,
            @Value("${pos.location.base-path:/v1/locations}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    @NonNull
    public Optional<UUID> fetchTopLevelLocationId() {
        try {
            LocationServiceResponse response = restClient
                    .get()
                    .uri(basePath + "/top-level")
                    .header("X-User", "pos-people-service")
                    .header("X-Authorities", "location:read")
                    .retrieve()
                    .body(LocationServiceResponse.class);
            if (response == null || response.id() == null) {
                log.warn("Location service returned no top-level location");
                return Optional.empty();
            }
            return Optional.of(response.id());
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch top-level location from location service: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Minimal mirror of the pos-location LocationResponseDTO contract. */
    private record LocationServiceResponse(UUID id) {}
}
