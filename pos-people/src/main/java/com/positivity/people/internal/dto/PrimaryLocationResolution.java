package com.positivity.people.internal.dto;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service-layer result of resolving the current user's primary location.
 *
 * Issue: #1636
 *
 * @param locationId resolved location id, never null
 * @param defaulted  true when the user had no active primary assignment and the
 *                   platform's top-level location was substituted as the default
 */
public record PrimaryLocationResolution(@NonNull UUID locationId, boolean defaulted) {}
