package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.repository.PickTaskRepository;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link PickListLocationScopeGuard} (PR #2227 review items 1-3): the HTTP-boundary
 * component that replaced the location-scope checks removed from {@link PickListServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PickListLocationScopeGuard (ADR-0061 §3, #2204, #2227)")
class PickListLocationScopeGuardTest {

    private static final UUID PICK_LIST_ID = UUID.fromString("00000000-0000-0000-0000-000000000070");
    private static final UUID SITE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SITE_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID OTHER_SITE = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final String PERMISSION = "inventory:pick_list:execute";

    /** Trivial resolver: every location is its own (and only) ancestor on both dimensions. */
    private static final LocationAncestorResolver SELF_RESOLVER =
            locationId -> new AncestorSets(Set.of(locationId), Set.of(locationId));

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private ForecastSiteResolver forecastSiteResolver;

    private PickListLocationScopeGuard guard;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        guard = new PickListLocationScopeGuard(pickTaskRepository, forecastSiteResolver);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(LocationScope scope) {
        var authentication = new UsernamePasswordAuthenticationToken(
                "scoped-picker", null, List.of(new SimpleGrantedAuthority(PERMISSION)));
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "scoped-picker",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static LocationScope scopedTo(UUID... nodes) {
        return LocationScope.of(Set.of(), Set.of(PERMISSION), Optional.of(Set.of(nodes)), true, SELF_RESOLVER);
    }

    private PickTaskEntity taskAt(UUID pickTaskId, UUID suggestedLocationId) {
        return PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .productId(UUID.randomUUID())
                .sku("SKU-1")
                .quantityRequired(1)
                .suggestedLocationId(suggestedLocationId)
                .build();
    }

    private void givenTwoSiteList() {
        UUID binA = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        UUID binB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        when(pickTaskRepository.findByPickList_PickListId(PICK_LIST_ID))
                .thenReturn(List.of(
                        taskAt(UUID.fromString("00000000-0000-0000-0000-0000000000c1"), binA),
                        taskAt(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), binB)));
        when(forecastSiteResolver.resolveForecastSite(binA)).thenReturn(SITE_A);
        when(forecastSiteResolver.resolveForecastSite(binB)).thenReturn(SITE_B);
    }

    // ─── resolveSites ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveSites returns every distinct resolved site across the list's tasks")
    void resolveSites_multiSiteList_returnsBothDistinctSites() {
        givenTwoSiteList();

        assertThat(guard.resolveSites(PICK_LIST_ID)).containsExactlyInAnyOrder(SITE_A, SITE_B);
    }

    @Test
    @DisplayName("resolveSites is empty when the list has no tasks")
    void resolveSites_noTasks_returnsEmpty() {
        when(pickTaskRepository.findByPickList_PickListId(PICK_LIST_ID)).thenReturn(List.of());

        assertThat(guard.resolveSites(PICK_LIST_ID)).isEmpty();
    }

    // ─── require: PR #2227 review item 2 — every distinct site of a multi-site list ─────

    @Test
    @DisplayName("require: two-site list, caller covers both sites — passes")
    void require_twoSiteList_bothInReach_passes() {
        authenticate(scopedTo(SITE_A, SITE_B));
        givenTwoSiteList();

        guard.require(PICK_LIST_ID, PERMISSION);
        // No exception: reaching here is the assertion.
    }

    @Test
    @DisplayName("require: two-site list, caller covers only one site — denies (not findFirst)")
    void require_twoSiteList_onlyOneInReach_denies() {
        authenticate(scopedTo(SITE_A));
        givenTwoSiteList();

        assertThatThrownBy(() -> guard.require(PICK_LIST_ID, PERMISSION))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("require: two-site list, caller covers neither site — denies")
    void require_twoSiteList_neitherInReach_denies() {
        authenticate(scopedTo(OTHER_SITE));
        givenTwoSiteList();

        assertThatThrownBy(() -> guard.require(PICK_LIST_ID, PERMISSION))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("require: no resolvable site — not gated, passes even when the caller's reach excludes everything")
    void require_noResolvableSite_notGated() {
        authenticate(scopedTo(OTHER_SITE));
        when(pickTaskRepository.findByPickList_PickListId(PICK_LIST_ID)).thenReturn(List.of());

        guard.require(PICK_LIST_ID, PERMISSION);
    }

    @Test
    @DisplayName("require: pre-rollout token (no loc_* claims) — unchanged even on an out-of-reach site")
    void require_preRolloutToken_unchanged() {
        authenticate(LocationScope.unscoped());
        givenTwoSiteList();

        guard.require(PICK_LIST_ID, PERMISSION);
    }

    // ─── requireForLocation: PR #2227 review item 3 — the scanned location's own site ───

    @Test
    @DisplayName("requireForLocation: scanned location's site in reach — passes")
    void requireForLocation_inReach_passes() {
        authenticate(scopedTo(SITE_A));
        UUID scannedLocation = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        when(forecastSiteResolver.resolveForecastSite(scannedLocation)).thenReturn(SITE_A);

        guard.requireForLocation(scannedLocation, PERMISSION);
    }

    @Test
    @DisplayName("requireForLocation: scanned location resolves to a different, out-of-reach site — denies")
    void requireForLocation_scannedAtDifferentSite_denies() {
        authenticate(scopedTo(SITE_A));
        UUID scannedLocation = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
        when(forecastSiteResolver.resolveForecastSite(scannedLocation)).thenReturn(SITE_B);

        assertThatThrownBy(() -> guard.requireForLocation(scannedLocation, PERMISSION))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    // ─── isWithinReach: PR #2227 review item 4 — narrowing, never throws ────────

    @Test
    @DisplayName("isWithinReach: two-site list, both sites covered — true")
    void isWithinReach_bothSitesCovered_true() {
        authenticate(scopedTo(SITE_A, SITE_B));
        givenTwoSiteList();

        assertThat(guard.isWithinReach(PICK_LIST_ID, PERMISSION)).isTrue();
    }

    @Test
    @DisplayName("isWithinReach: two-site list, only one site covered — false, and never throws")
    void isWithinReach_onlyOneSiteCovered_false() {
        authenticate(scopedTo(SITE_A));
        givenTwoSiteList();

        assertThat(guard.isWithinReach(PICK_LIST_ID, PERMISSION)).isFalse();
    }

    @Test
    @DisplayName("isWithinReach: no resolvable site — always true (nothing to narrow on)")
    void isWithinReach_noResolvableSite_true() {
        authenticate(scopedTo(OTHER_SITE));
        when(pickTaskRepository.findByPickList_PickListId(PICK_LIST_ID)).thenReturn(List.of());

        assertThat(guard.isWithinReach(PICK_LIST_ID, PERMISSION)).isTrue();
    }
}
