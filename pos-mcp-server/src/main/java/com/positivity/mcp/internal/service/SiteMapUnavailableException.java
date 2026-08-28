package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;

/**
 * Thrown by {@link SiteMapService#getSiteMap()} / {@link SiteMapService#refresh()} only when a fetch
 * fails <em>and</em> there is no last-known-good copy to serve (a cold cache). Once any successful
 * fetch has been cached, later fetch failures never surface this — the cached copy is returned
 * instead — so callers can treat this purely as a cold-start signal.
 */
public class SiteMapUnavailableException extends RuntimeException {

    public SiteMapUnavailableException(@NonNull String message) {
        super(message);
    }

    public SiteMapUnavailableException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
