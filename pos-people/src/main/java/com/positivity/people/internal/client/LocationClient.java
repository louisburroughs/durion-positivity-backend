package com.positivity.people.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Outbound client for the pos-location service.
 *
 * Issue: #1636
 */
public interface LocationClient {

    /**
     * Fetches the platform's top-level default location from the location service
     * ({@code GET /v1/locations/top-level}).
     *
     * @return the top-level location id, or empty when the location service has none or the
     * call fails (callers degrade rather than error)
     */
    @NonNull
    Optional<UUID> fetchTopLevelLocationId();
}
