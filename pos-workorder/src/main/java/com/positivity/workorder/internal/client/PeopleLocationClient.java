package com.positivity.workorder.internal.client;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for resolving the current user's primary location from pos-people.
 *
 * <p>
 * Calls the {@code GET /v1/people/me/primary-location} endpoint which
 * resolves the authenticated user's primary staffing assignment location.
 * Falls back gracefully when pos-people is unavailable.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeopleLocationClient {

    private final RestClient peopleServiceRestClient;

    /**
     * Resolve the current user's primary location from their staffing assignments.
     *
     * @return the primary location UUID, or empty if unavailable
     */
    @NonNull
    public Optional<UUID> resolveCurrentUserPrimaryLocation() {
        try {
            PrimaryLocationResponse response = peopleServiceRestClient
                    .get()
                    .uri("/v1/people/me/primary-location")
                    .retrieve()
                    .body(PrimaryLocationResponse.class);

            if (response == null || response.locationId() == null) {
                log.warn("pos-people returned empty primary location response");
                return Optional.empty();
            }

            log.debug("Resolved primary location {} for current user", response.locationId());
            return Optional.of(response.locationId());
        } catch (Exception ex) {
            log.warn(
                    "Failed to resolve primary location from pos-people, falling back to default: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Minimal response record for the primary location endpoint.
     * Avoids cross-module dependency on pos-people DTOs.
     */
    private record PrimaryLocationResponse(UUID locationId) {}
}
