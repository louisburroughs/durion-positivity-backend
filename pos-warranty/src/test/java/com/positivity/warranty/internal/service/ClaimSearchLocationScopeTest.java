package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.LocationScopeDeniedException;
import com.positivity.warranty.internal.dto.ClaimSummaryResponse;
import com.positivity.warranty.internal.entity.WarrantyClaim;
import com.positivity.warranty.internal.enums.ClaimStatus;
import com.positivity.warranty.internal.repository.ClaimNoteRepository;
import com.positivity.warranty.internal.repository.ClaimSettlementRepository;
import com.positivity.warranty.internal.repository.ClaimStatusHistoryRepository;
import com.positivity.warranty.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.warranty.internal.repository.PartReturnRepository;
import com.positivity.warranty.internal.repository.VendorReimbursementRepository;
import com.positivity.warranty.internal.repository.WarrantyClaimRepository;
import com.positivity.warranty.internal.repository.WarrantyPolicyRepository;
import com.positivity.warranty.internal.security.WarrantyPermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Location scope on {@code searchClaims} (ADR-0061 §3, #1885). {@code locationId} is an optional
 * filter, so this endpoint <em>narrows</em> rather than gates: a supplied location is checked
 * against the caller's reach and refused with {@code LOCATION_SCOPE_DENIED} when it is outside,
 * and without one the page is restricted to the caller's reach rather than refused — an empty
 * reach being an empty page, never every claim.
 *
 * <p>The reach expansion over the {@code ext_location} replica is
 * {@link LocationHierarchyService#reachableLocations}, pinned in
 * {@link LocationHierarchyServiceTest}; mocked here so this test pins what the service asks for
 * and what it does with the answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClaimService.search — location scope (#1885)")
class ClaimSearchLocationScopeTest {

    private static final UUID REGION_NODE = UUID.fromString("019200bb-0000-7000-8000-00000000a000");
    private static final UUID SHOP_A = UUID.fromString("019200bb-0000-7000-8000-00000000000a");
    private static final UUID SHOP_B = UUID.fromString("019200bb-0000-7000-8000-00000000000b");
    private static final UUID CLAIM_ID = UUID.fromString("019200bb-0000-7000-8000-000000000101");

    private static final Pageable PAGE = PageRequest.of(0, 20);

    @Mock
    private WarrantyClaimRepository claimRepository;

    @Mock
    private WarrantyPolicyRepository policyRepository;

    @Mock
    private ClaimStatusHistoryRepository statusHistoryRepository;

    @Mock
    private ClaimNoteRepository noteRepository;

    @Mock
    private ClaimSettlementRepository settlementRepository;

    @Mock
    private VendorReimbursementRepository reimbursementRepository;

    @Mock
    private PartReturnRepository partReturnRepository;

    @Mock
    private ClaimCodeService claimCodeService;

    @Mock
    private EligibilityService eligibilityService;

    @Mock
    private ExtVehicleReplicaRepository extVehicleReplicaRepository;

    @Mock
    private ClaimSnapshotPublisher claimSnapshotPublisher;

    @Mock
    private LocationHierarchyService locationHierarchyService;

    private ClaimServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ClaimServiceImpl(
                claimRepository,
                policyRepository,
                statusHistoryRepository,
                noteRepository,
                settlementRepository,
                reimbursementRepository,
                partReturnRepository,
                claimCodeService,
                eligibilityService,
                extVehicleReplicaRepository,
                claimSnapshotPublisher,
                locationHierarchyService,
                Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void clearCaller() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ caller shapes

    /** A post-rollout token whose claim:view permission is OTHER-scoped to {@link #REGION_NODE}. */
    private static void scopedCaller() {
        callerWith(LocationScope.of(
                Set.of(),
                Set.of(WarrantyPermissions.CLAIM_VIEW),
                Optional.of(Set.of(REGION_NODE)),
                true,
                locationId -> {
                    throw new AssertionError("the gate resolves ancestors through the module resolver, not here");
                }));
    }

    /** A scoped caller with a resolver that places {@code inReach} beneath {@link #REGION_NODE}. */
    private static void scopedCallerSeeing(UUID inReach) {
        callerWith(LocationScope.of(
                Set.of(),
                Set.of(WarrantyPermissions.CLAIM_VIEW),
                Optional.of(Set.of(REGION_NODE)),
                true,
                locationId -> locationId.equals(inReach)
                        ? new com.positivity.domainevents.location.LocationAncestry.AncestorSets(
                                Set.of(locationId), Set.of(locationId, REGION_NODE))
                        : new com.positivity.domainevents.location.LocationAncestry.AncestorSets(
                                Set.of(locationId), Set.of(locationId))));
    }

    /** A post-rollout token carrying the scope bitset but no assigned nodes: reach is nothing. */
    private static void scopedCallerWithoutNodes() {
        callerWith(LocationScope.of(
                Set.of(), Set.of(WarrantyPermissions.CLAIM_VIEW), Optional.empty(), true, locationId -> {
                    throw new AssertionError("no resolver call is needed when the reach carries no nodes");
                }));
    }

    /** A pre-rollout token: no {@code loc_*} claims at all. */
    private static void preRolloutCaller() {
        var authentication = new TestingAuthenticationToken("scope-test-user", null, "ROLE_USER");
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "scope-test-user"));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static void callerWith(LocationScope scope) {
        var authentication = new TestingAuthenticationToken("scope-test-user", null, "ROLE_USER");
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "scope-test-user",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static WarrantyClaim claimAt(UUID locationId) {
        return WarrantyClaim.builder()
                .id(CLAIM_ID)
                .claimCode("WC-2026-000123")
                .locationId(locationId)
                .status(ClaimStatus.DRAFT)
                .createdAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
    }

    private static boolean isOtherReachOn(Reach reach) {
        return reach.nodes().equals(Set.of(REGION_NODE)) && reach.dimensions().equals(Set.of(Dimension.OTHER));
    }

    // ------------------------------------------------------------------ gate: a supplied filter

    @Test
    @DisplayName("a supplied location inside the caller's reach is searched as asked")
    void suppliedLocationInReach() {
        scopedCallerSeeing(SHOP_A);
        when(claimRepository.search(isNull(), isNull(), isNull(), eq(SHOP_A), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(claimAt(SHOP_A))));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, null, SHOP_A, PAGE);

        assertThat(page.getContent()).hasSize(1);
        verify(claimRepository).search(isNull(), isNull(), isNull(), eq(SHOP_A), eq(PAGE));
        verify(locationHierarchyService, never()).reachableLocations(any());
    }

    @Test
    @DisplayName("a supplied location outside the caller's reach is refused, and no query runs")
    void suppliedLocationOutOfReach() {
        scopedCallerSeeing(SHOP_A);

        assertThatThrownBy(() -> service.search(null, null, null, null, SHOP_B, PAGE))
                .isInstanceOf(LocationScopeDeniedException.class);

        verifyNoInteractions(claimRepository);
    }

    // ------------------------------------------------------------------ narrow: no filter

    @Test
    @DisplayName("without a filter, a scoped caller's page is narrowed to the reach expanded over the replica")
    void unfilteredNarrowsToReach() {
        scopedCaller();
        when(locationHierarchyService.reachableLocations(
                        org.mockito.ArgumentMatchers.argThat(ClaimSearchLocationScopeTest::isOtherReachOn)))
                .thenReturn(Set.of(REGION_NODE, SHOP_A));
        when(claimRepository.searchWithinLocations(
                        isNull(), isNull(), isNull(), eq(Set.of(REGION_NODE, SHOP_A)), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(claimAt(SHOP_A))));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, null, null, PAGE);

        assertThat(page.getContent()).hasSize(1);
        verify(claimRepository, never()).search(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a scoped caller who reaches nothing gets an empty page, not every claim and not a 403")
    void emptyReachIsAnEmptyPage() {
        scopedCallerWithoutNodes();

        Page<ClaimSummaryResponse> page = service.search(null, null, null, null, null, PAGE);

        assertThat(page.getContent()).isEmpty();
        verifyNoInteractions(claimRepository);
    }

    @Test
    @DisplayName("the claimCode short-circuit drops a match outside the caller's reach")
    void claimCodeShortCircuitRespectsReach() {
        scopedCaller();
        when(locationHierarchyService.reachableLocations(any())).thenReturn(Set.of(REGION_NODE, SHOP_A));
        when(claimRepository.findByClaimCode("WC-2026-000123")).thenReturn(Optional.of(claimAt(SHOP_B)));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, "WC-2026-000123", null, PAGE);

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    @DisplayName("the claimCode short-circuit keeps a match inside the caller's reach")
    void claimCodeShortCircuitKeepsInReachMatch() {
        scopedCaller();
        when(locationHierarchyService.reachableLocations(any())).thenReturn(Set.of(REGION_NODE, SHOP_A));
        when(claimRepository.findByClaimCode("WC-2026-000123")).thenReturn(Optional.of(claimAt(SHOP_A)));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, "WC-2026-000123", null, PAGE);

        assertThat(page.getContent()).hasSize(1);
    }

    // ------------------------------------------------------------------ unchanged callers

    @Test
    @DisplayName("a pre-rollout token searches unrestricted, exactly as before")
    void preRolloutUnchanged() {
        preRolloutCaller();
        when(claimRepository.search(isNull(), isNull(), isNull(), isNull(), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(claimAt(SHOP_B))));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, null, null, PAGE);

        assertThat(page.getContent()).hasSize(1);
        verify(locationHierarchyService, never()).reachableLocations(any());
    }

    @Test
    @DisplayName("a caller whose claim:view grant is global searches unrestricted and may name any location")
    void globalGrantUnchanged() {
        callerWith(LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(REGION_NODE)), true, locationId -> {
            throw new AssertionError("a global grant is never location-resolved");
        }));
        when(claimRepository.search(isNull(), isNull(), isNull(), eq(SHOP_B), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(claimAt(SHOP_B))));

        Page<ClaimSummaryResponse> page = service.search(null, null, null, null, SHOP_B, PAGE);

        assertThat(page.getContent()).hasSize(1);
        verify(locationHierarchyService, never()).reachableLocations(any());
    }
}
