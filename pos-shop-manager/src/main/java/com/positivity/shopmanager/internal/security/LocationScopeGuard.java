package com.positivity.shopmanager.internal.security;

import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.security.common.SecurityContextHelper;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Location-scope gate for this module's location-parameterised endpoints (ADR-0061 §3, #1872).
 *
 * <p>{@code @PreAuthorize} answers "may this caller do X"; {@link LocationScope#require} answers
 * "…at this location". For an endpoint guarded by a single permission the controller calls
 * {@code SecurityContextHelper.locationScope().require(permission, locationId)} directly. This
 * class covers the {@code hasAnyAuthority(a, b)} case: the caller may hold either alternate, each
 * may be scoped differently, and the request is denied only when <em>none</em> of the alternates
 * the caller actually holds covers the location. Requiring on an alternate the caller does not
 * hold would be wrong in both directions — an unscoped permission the caller lacks would pass, and
 * a scoped one the caller lacks would deny.
 *
 * <p>Absent scope claims (a pre-rollout token) make {@link LocationScope#covers} true for every
 * permission, so this guard is a no-op for them, exactly as {@code require} is.
 */
public final class LocationScopeGuard {

    private LocationScopeGuard() {}

    /**
     * Requires that at least one of the permissions the caller holds among {@code alternates}
     * covers {@code locationId}.
     *
     * @param locationId the location the request acts on
     * @param alternates the permissions named by the endpoint's {@code hasAnyAuthority}, in the
     *     order they appear there
     * @throws LocationScopeDeniedException when none of the held alternates covers the location;
     *     the exception names the first held alternate (or the first alternate when, contrary to
     *     the {@code @PreAuthorize} contract, the caller holds none)
     */
    public static void requireAny(@NonNull UUID locationId, @NonNull String... alternates) {
        requireAny(locationId.toString(), alternates);
    }

    /**
     * {@link #requireAny(UUID, String...)} for a location id that may not be a UUID — e.g. a
     * resource whose location is missing, passed as {@code ""}, which a scoped caller can never
     * cover and an unscoped caller is never asked about.
     *
     * @param locationId the location the request acts on, as text
     * @param alternates the permissions named by the endpoint's {@code hasAnyAuthority}
     * @throws LocationScopeDeniedException when none of the held alternates covers the location
     */
    public static void requireAny(@NonNull String locationId, @NonNull String... alternates) {
        if (alternates.length == 0) {
            throw new IllegalArgumentException("at least one permission alternate is required");
        }
        LocationScope scope = SecurityContextHelper.locationScope();
        Set<String> held = SecurityContextHelper.getAuthorities();
        String firstHeld = null;
        for (String permission : alternates) {
            if (!held.contains(permission)) {
                continue;
            }
            if (scope.covers(permission, locationId)) {
                return;
            }
            if (firstHeld == null) {
                firstHeld = permission;
            }
        }
        throw new LocationScopeDeniedException(firstHeld == null ? alternates[0] : firstHeld, locationId);
    }
}
