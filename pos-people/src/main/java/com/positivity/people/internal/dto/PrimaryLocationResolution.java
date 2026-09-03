package com.positivity.people.internal.dto;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service-layer result of resolving the current user's primary location.
 *
 * Issue: #1636, #1680
 *
 * @param locationId   resolved location id, never null
 * @param locationName denormalized display name of the resolved location, or null when the
 *                      event-fed {@code ext_location} replica has no matching row or a blank
 *                      name (issue #1680)
 * @param defaulted    true when the user had no active primary assignment and the
 *                      platform's top-level location was substituted as the default
 */
public record PrimaryLocationResolution(
        @NonNull UUID locationId, @Nullable String locationName, boolean defaulted) {}
