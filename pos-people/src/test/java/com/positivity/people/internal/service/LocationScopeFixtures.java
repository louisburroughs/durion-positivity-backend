package com.positivity.people.internal.service;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Test-side helpers for authenticating a caller with a given {@link LocationScope} in service
 * unit tests (ADR-0061, #1872). The resolver is an in-memory map of inclusive ancestor sets so a
 * test can pin the gate/narrow decision without a database; {@code TimeEntryLocationScopeTest}
 * covers the real replica walk.
 */
public final class LocationScopeFixtures {

    private LocationScopeFixtures() {}

    /** A resolver over fixed ancestor sets; an unknown location answers {@link AncestorSets#EMPTY}. */
    public static LocationAncestorResolver resolverOf(Map<UUID, AncestorSets> ancestorsByLocation) {
        return locationId -> ancestorsByLocation.getOrDefault(locationId, AncestorSets.EMPTY);
    }

    /** Ancestor sets for a location whose only ancestor on both dimensions is itself. */
    public static AncestorSets selfOnly(UUID locationId) {
        return new AncestorSets(Set.of(locationId), Set.of(locationId));
    }

    /**
     * Ancestor sets for a location beneath {@code parent} on the {@code OTHER} dimension only —
     * the FINANCIAL rollup stops at the location itself.
     */
    public static AncestorSets underOther(UUID locationId, UUID parent) {
        return new AncestorSets(Set.of(locationId), Set.of(locationId, parent));
    }

    /** A scope in which {@code permission} is scoped on {@code dimensions} at {@code nodes}. */
    public static LocationScope scopedOn(
            String permission, Set<Dimension> dimensions, LocationAncestorResolver resolver, UUID... nodes) {
        Set<String> financial = dimensions.contains(Dimension.FINANCIAL) ? Set.of(permission) : Set.of();
        Set<String> other = dimensions.contains(Dimension.OTHER) ? Set.of(permission) : Set.of();
        return LocationScope.of(financial, other, Optional.of(Set.of(nodes)), true, resolver);
    }

    /** A scope whose claims are present but in which no permission is location-scoped. */
    public static LocationScope globalWithClaims(LocationAncestorResolver resolver) {
        return LocationScope.of(Set.of(), Set.of(), Optional.empty(), true, resolver);
    }

    /** Authenticates {@code username} with the given scope in the current security context. */
    public static void callerWith(String username, LocationScope scope) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(username, null, "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                username,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** Authenticates a caller whose token predates the scope claims (no scope detail at all). */
    public static void preRolloutCaller(String username) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(username, null, "ROLE_USER");
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static void clearCaller() {
        SecurityContextHolder.clearContext();
    }
}
