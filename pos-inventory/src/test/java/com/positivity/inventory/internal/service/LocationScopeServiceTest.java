package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The module's gate/narrow helper (ADR-0061 §3, #1872) against the decision table, including the
 * {@code hasAnyAuthority(a, b)} alternates rule: deny only when no held alternate covers, narrow
 * only when every held alternate is scoped.
 */
@DisplayName("LocationScopeService")
class LocationScopeServiceTest {

    private static final String VIEW = "inventory:scrap:view";
    private static final String APPROVE = "inventory:scrap:approve";

    private static final UUID SHOP = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30");
    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31");
    private static final UUID OTHER_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32");
    private static final UUID FIN_ROOT = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a33");

    /** SHOP sits under SITE on OTHER and under FIN_ROOT on FINANCIAL. */
    private static final LocationAncestorResolver RESOLVER = locationId ->
            SHOP.equals(locationId) ? new AncestorSets(Set.of(SHOP, FIN_ROOT), Set.of(SHOP, SITE)) : AncestorSets.EMPTY;

    private final LocationHierarchyService hierarchy = mock(LocationHierarchyService.class);
    private final LocationScopeService service = new LocationScopeService(hierarchy);

    @BeforeEach
    void stubHierarchy() {
        when(hierarchy.descendantsOf(SITE, Dimension.OTHER)).thenReturn(Set.of(SITE, SHOP));
        when(hierarchy.descendantsOf(FIN_ROOT, Dimension.FINANCIAL)).thenReturn(Set.of(FIN_ROOT, SHOP));
        when(hierarchy.descendantsOf(OTHER_SITE, Dimension.OTHER)).thenReturn(Set.of(OTHER_SITE));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void caller(LocationScope scope, String... authorities) {
        var token = new UsernamePasswordAuthenticationToken(
                "user",
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "user",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static LocationScope scoped(Set<String> financial, Set<String> other, UUID... nodes) {
        return LocationScope.of(financial, other, Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    @Nested
    @DisplayName("require")
    class Require {

        @Test
        @DisplayName("pre-rollout caller (no claims): passes, hierarchy never consulted")
        void unscopedPasses() {
            caller(LocationScope.unscoped(), VIEW);

            assertThatCode(() -> service.require(SHOP, VIEW)).doesNotThrowAnyException();
            assertThatCode(() -> service.require(null, VIEW)).doesNotThrowAnyException();
            verifyNoInteractions(hierarchy);
        }

        @Test
        @DisplayName("scoped caller: in reach passes, out of reach is LOCATION_SCOPE_DENIED")
        void scopedGate() {
            caller(scoped(Set.of(), Set.of(VIEW), SITE), VIEW);
            assertThatCode(() -> service.require(SHOP, VIEW)).doesNotThrowAnyException();

            caller(scoped(Set.of(), Set.of(VIEW), OTHER_SITE), VIEW);
            assertThatThrownBy(() -> service.require(SHOP, VIEW))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .hasMessageContaining(VIEW);
        }

        @Test
        @DisplayName("a record with no location denies a scoped caller and passes a global one")
        void nullLocationFailsClosed() {
            caller(scoped(Set.of(), Set.of(VIEW), SITE), VIEW);
            assertThatThrownBy(() -> service.require(null, VIEW)).isInstanceOf(LocationScopeDeniedException.class);

            caller(scoped(Set.of(), Set.of(), SITE), VIEW);
            assertThatCode(() -> service.require(null, VIEW)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("alternates: a held global alternate covers even when the scoped one does not")
        void alternatesAnyHeldCovers() {
            caller(scoped(Set.of(), Set.of(VIEW), OTHER_SITE), VIEW, APPROVE);

            assertThatCode(() -> service.require(SHOP, VIEW, APPROVE)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("alternates: an alternate the caller does not hold cannot cover")
        void alternatesOnlyHeldCount() {
            caller(scoped(Set.of(), Set.of(VIEW), OTHER_SITE), VIEW);

            assertThatThrownBy(() -> service.require(SHOP, VIEW, APPROVE))
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .hasMessageContaining(VIEW);
        }

        @Test
        @DisplayName("no permissions is a programming error")
        void requiresAtLeastOnePermission() {
            caller(LocationScope.unscoped(), VIEW);

            assertThatThrownBy(() -> service.require(SHOP)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reachOf / narrowTo")
    class Reach {

        @Test
        @DisplayName("pre-rollout and global callers are not narrowed")
        void unscopedAndGlobalAreEmpty() {
            caller(LocationScope.unscoped(), VIEW);
            assertThat(service.reachOf(VIEW)).isEmpty();

            caller(scoped(Set.of(), Set.of(), SITE), VIEW);
            assertThat(service.reachOf(VIEW)).isEmpty();
            verifyNoInteractions(hierarchy);
        }

        @Test
        @DisplayName("scoped caller: the union of inclusive descendants per assigned node and dimension")
        void scopedExpandsEveryNodeOnEveryDimension() {
            caller(scoped(Set.of(VIEW), Set.of(VIEW), SITE, FIN_ROOT), VIEW);

            Optional<Set<UUID>> reach = service.reachOf(VIEW);

            assertThat(reach).isPresent();
            assertThat(reach.get()).containsExactlyInAnyOrder(SITE, SHOP, FIN_ROOT);
        }

        @Test
        @DisplayName("scoped caller with the nodes claim absent reaches nothing (fail closed), not everything")
        void scopedWithoutNodesIsPresentAndEmpty() {
            caller(LocationScope.of(Set.of(), Set.of(VIEW), Optional.empty(), true, RESOLVER), VIEW);

            Optional<Set<UUID>> reach = service.reachOf(VIEW);

            assertThat(reach).isPresent();
            assertThat(reach.get()).isEmpty();
        }

        @Test
        @DisplayName("alternates: one held global alternate means no narrowing at all")
        void alternatesGlobalWins() {
            caller(scoped(Set.of(), Set.of(VIEW), SITE), VIEW, APPROVE);

            assertThat(service.reachOf(VIEW, APPROVE)).isEmpty();
        }

        @Test
        @DisplayName("alternates: every held alternate scoped narrows to the union of their reaches")
        void alternatesAllScopedUnion() {
            caller(scoped(Set.of(APPROVE), Set.of(VIEW), SITE, FIN_ROOT), VIEW, APPROVE);

            Optional<Set<UUID>> reach = service.reachOf(VIEW, APPROVE);

            assertThat(reach).isPresent();
            assertThat(reach.get()).containsExactlyInAnyOrder(SITE, SHOP, FIN_ROOT);
        }

        @Test
        @DisplayName("narrowTo with a named location gates it and applies no narrowing")
        void narrowToNamedLocationGates() {
            caller(scoped(Set.of(), Set.of(VIEW), SITE), VIEW);
            assertThat(service.narrowTo(SHOP, VIEW)).isEmpty();

            caller(scoped(Set.of(), Set.of(VIEW), OTHER_SITE), VIEW);
            assertThatThrownBy(() -> service.narrowTo(SHOP, VIEW)).isInstanceOf(LocationScopeDeniedException.class);
        }

        @Test
        @DisplayName("narrowTo without a location is reachOf")
        void narrowToWithoutLocationIsReach() {
            caller(scoped(Set.of(), Set.of(VIEW), SITE), VIEW);

            assertThat(service.narrowTo(null, VIEW)).contains(Set.of(SITE, SHOP));
        }
    }

    @Test
    @DisplayName("withinLocations refuses an empty reach: that case is decided before any query")
    void withinLocationsRejectsEmptyReach() {
        assertThatThrownBy(() -> LocationScopeService.withinLocations("locationId", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
