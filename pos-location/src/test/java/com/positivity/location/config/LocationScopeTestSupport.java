package com.positivity.location.config;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * Shared fixture for the controller-slice proofs of ADR-0061 location-scope enforcement (#1872).
 *
 * <p>The {@link LocationScope} is injected through the authentication details map exactly where
 * {@code GatewayAuthoritiesFilter} puts it, with a map-backed {@link LocationAncestorResolver}
 * standing in for {@code LocationHierarchyService}. The caller is installed through
 * {@link TestSecurityContextHolder}, which Boot's MockMvc security configurer copies into each
 * request. Unlike {@link TestSecurityConfig}, no filter overwrites the caller, so a test can
 * choose a scoped, global or pre-rollout identity per request.
 *
 * <p>{@link SliceConfig} enables method security so {@code @PreAuthorize} is enforced alongside
 * the scope gate; see its note on why the slice carries no filter chain.
 */
public final class LocationScopeTestSupport {

    public static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    public static final String USERNAME = "location-scope-test-user";

    /** The node the scoped caller is assigned: a region above {@link #SITE_IN_REACH}. */
    public static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");

    /** A site under {@link #REGION_NODE} on the OTHER dimension. */
    public static final UUID SITE_IN_REACH = UUID.fromString("019200bb-0000-7000-8000-00000000000a");

    /** A site the resolver knows but that sits under no node the caller holds. */
    public static final UUID SITE_OUT_OF_REACH = UUID.fromString("019200bb-0000-7000-8000-00000000000b");

    /** An id the resolver does not hold at all: a scoped caller fails closed on it. */
    public static final UUID UNKNOWN_LOCATION = UUID.fromString("019200bb-0000-7000-8000-0000000000ff");

    private static final Map<UUID, AncestorSets> TREE = Map.of(
            SITE_IN_REACH, new AncestorSets(Set.of(SITE_IN_REACH), Set.of(SITE_IN_REACH, REGION_NODE)),
            SITE_OUT_OF_REACH, new AncestorSets(Set.of(SITE_OUT_OF_REACH), Set.of(SITE_OUT_OF_REACH)));

    public static final LocationAncestorResolver RESOLVER =
            locationId -> TREE.getOrDefault(locationId, AncestorSets.EMPTY);

    private LocationScopeTestSupport() {}

    /**
     * A post-rollout token whose {@code scopedPermission} is OTHER-scoped to {@link #REGION_NODE}.
     *
     * @param scopedPermission the permission under test, also granted as an authority
     * @param extraAuthorities any further authorities the caller holds globally
     */
    public static Authentication scopedOn(String scopedPermission, String... extraAuthorities) {
        return caller(
                withFirst(scopedPermission, extraAuthorities),
                LocationScope.of(Set.of(), Set.of(scopedPermission), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A post-rollout token that carries claims but whose permissions are all global. */
    public static Authentication globalWithClaims(String... authorities) {
        return caller(
                List.of(authorities),
                LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, RESOLVER));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all, so no scope detail is attached. */
    public static Authentication preRollout(String... authorities) {
        return caller(List.of(authorities), null);
    }

    /** Installs the caller for the next request, exactly as {@code @WithMockUser} would. */
    public static Authentication as(Authentication caller) {
        TestSecurityContextHolder.setAuthentication(caller);
        return caller;
    }

    public static void clearCaller() {
        TestSecurityContextHolder.clearContext();
    }

    private static List<String> withFirst(String first, String... rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return Arrays.asList(all);
    }

    private static Authentication caller(List<String> authorities, @Nullable LocationScope scope) {
        var token = new UsernamePasswordAuthenticationToken(
                USERNAME,
                null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        Map<String, Object> details = scope == null
                ? Map.of(GatewaySecurityConstants.DETAIL_USERNAME, USERNAME)
                : Map.of(
                        GatewaySecurityConstants.DETAIL_USERNAME,
                        USERNAME,
                        GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                        scope);
        token.setDetails(details);
        return token;
    }

    /**
     * Slice wiring: method security so {@code @PreAuthorize} is enforced alongside the scope gate,
     * and a fixed clock for the advices. Deliberately no {@code SecurityFilterChain}: the slice
     * has none by default, so the caller installed on the thread by
     * {@link TestSecurityContextHolder} is what method security and
     * {@code SecurityContextHelper} see, and no CSRF filter stands in front of PUT/PATCH/DELETE.
     * A chain would swap that context for an empty one from its repository.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity(prePostEnabled = true)
    public static class SliceConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }
    }
}
