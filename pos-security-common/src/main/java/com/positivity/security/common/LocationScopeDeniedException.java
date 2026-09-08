package com.positivity.security.common;

import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;

/**
 * The caller holds the permission but not for the requested location (ADR-0061 §3, #1870).
 *
 * <p>An {@link AccessDeniedException} so that Spring Security and every module's existing 403
 * handling treat it as a denial, but a distinct type carrying {@link #ERROR_CODE} so a client can
 * tell "you may not do this" from "you may not do this <em>here</em>" — the first needs a
 * different role, the second a different location or a wider assignment.
 * {@link LocationScopeDeniedExceptionHandler} maps it onto the {@code ApiError} envelope.
 *
 * <p>The message names the permission but never the location id, which is caller-supplied and
 * must not be reflected into a response body.
 */
public class LocationScopeDeniedException extends AccessDeniedException {

    /** The {@code ApiError.code} a client sees for this denial. */
    public static final String ERROR_CODE = "LOCATION_SCOPE_DENIED";

    private final String permission;
    private final String locationId;

    /**
     * @param permission the permission checked, e.g. {@code workorder:wip:view}
     * @param locationId the location the caller asked for, retained for logging only
     */
    public LocationScopeDeniedException(@NonNull String permission, @NonNull String locationId) {
        super("Permission '" + permission + "' is location-scoped and does not cover the requested location");
        this.permission = permission;
        this.locationId = locationId;
    }

    /**
     * @return the permission the caller holds but not for the requested location
     */
    public @NonNull String permission() {
        return permission;
    }

    /**
     * @return the requested location id — for logs, never for the response body
     */
    public @NonNull String locationId() {
        return locationId;
    }
}
