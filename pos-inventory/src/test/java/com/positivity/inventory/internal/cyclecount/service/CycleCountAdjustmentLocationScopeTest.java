package com.positivity.inventory.internal.cyclecount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.domainevents.location.LocationAncestry.Dimension;
import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.entity.CycleCountAdjustment;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.repository.CycleCountAdjustmentRepository;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.SkuCostStateRepository;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.ApprovalThresholdEvaluator;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import com.positivity.inventory.internal.service.CostingMethodResolver;
import com.positivity.inventory.internal.service.CycleCountConflictDetector;
import com.positivity.inventory.internal.service.InventoryFactPublisher;
import com.positivity.inventory.internal.service.LedgerPostingFailureRecorder;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.inventory.internal.service.LocationHierarchyService;
import com.positivity.inventory.internal.service.LocationScopeService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.Clock;
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
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0061 location scope on the cycle count adjustment approval path (#2151), against the real
 * {@link LocationScopeService}: {@code approve}, {@code reject} and the by-id read are gates on the
 * adjustment's own location, the lists are narrowed to the caller's reach, and an adjustment with no
 * location is denied to a scoped caller and open to a global one.
 */
@DisplayName("CycleCountAdjustmentServiceImpl location scope (#2151)")
class CycleCountAdjustmentLocationScopeTest {

    private static final String APPROVE = InventoryPermissionRegistry.ADJUSTMENT_APPROVE;
    private static final String VIEW = InventoryPermissionRegistry.ADJUSTMENT_VIEW;

    private static final UUID SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31");
    private static final UUID BIN = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30");
    private static final UUID OTHER_SITE = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32");
    private static final UUID ADJUSTMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40");

    /** BIN sits under SITE; OTHER_SITE is unrelated. */
    private static final LocationAncestorResolver RESOLVER = locationId -> {
        if (BIN.equals(locationId)) {
            return new AncestorSets(Set.of(BIN), Set.of(BIN, SITE));
        }
        if (SITE.equals(locationId)) {
            return new AncestorSets(Set.of(SITE), Set.of(SITE));
        }
        if (OTHER_SITE.equals(locationId)) {
            return new AncestorSets(Set.of(OTHER_SITE), Set.of(OTHER_SITE));
        }
        return AncestorSets.EMPTY;
    };

    private final CycleCountAdjustmentRepository adjustmentRepository = mock(CycleCountAdjustmentRepository.class);
    private final LocationHierarchyService hierarchy = mock(LocationHierarchyService.class);
    private final InventoryLedgerEntryRepository ledgerRepository = mock(InventoryLedgerEntryRepository.class);
    private final LedgerPostingService ledgerPostingService = mock(LedgerPostingService.class);
    private CycleCountAdjustmentServiceImpl service;

    @BeforeEach
    void setUp() {
        when(hierarchy.descendantsOf(SITE, Dimension.OTHER)).thenReturn(Set.of(SITE, BIN));
        when(hierarchy.descendantsOf(OTHER_SITE, Dimension.OTHER)).thenReturn(Set.of(OTHER_SITE));
        service = new CycleCountAdjustmentServiceImpl(
                adjustmentRepository,
                ledgerRepository,
                ledgerPostingService,
                mock(ApprovalThresholdEvaluator.class),
                mock(ApplicationEventPublisher.class),
                Clock.systemUTC(),
                mock(CycleCountTaskRepository.class),
                mock(CycleCountPlanRepository.class),
                mock(CycleCountConflictDetector.class),
                mock(SkuCostStateRepository.class),
                mock(CostingMethodResolver.class),
                mock(BaseUnitOfMeasureResolver.class),
                new LocationScopeService(hierarchy),
                mock(LedgerPostingFailureRecorder.class),
                mock(InventoryFactPublisher.class));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A caller whose {@code scopedPermissions} are location-scoped to {@code nodes}; others are global. */
    private static void caller(Set<String> scopedPermissions, Set<UUID> nodes, String... authorities) {
        LocationScope scope = LocationScope.of(Set.of(), scopedPermissions, Optional.of(nodes), true, RESOLVER);
        var token = new UsernamePasswordAuthenticationToken(
                "approver",
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
        token.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                "approver",
                GatewaySecurityConstants.DETAIL_USER_ID,
                "approver-id",
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void scopedApprover(UUID... nodes) {
        caller(Set.of(APPROVE, VIEW), Set.of(nodes), APPROVE);
    }

    private static void globalApprover() {
        caller(Set.of(), Set.of(SITE), APPROVE);
    }

    private static CycleCountAdjustment pending(UUID locationId) {
        return CycleCountAdjustment.builder()
                .adjustmentId(ADJUSTMENT_ID)
                .stockItemId("SKU-1")
                .locationId(locationId)
                .reasonCode("CYCLE_COUNT_SHRINK")
                .quantityChange(new BigDecimal("-2"))
                .costAtTimeOfAdjustment(BigDecimal.TEN)
                .quantityOnHandBefore(new BigDecimal("10"))
                .countedQuantity(new BigDecimal("8"))
                .createdByUserId("counter")
                .status(AdjustmentStatus.PENDING_APPROVAL)
                .build();
    }

    private static RejectAdjustmentRequest rejection() {
        return RejectAdjustmentRequest.builder()
                .rejectorUserId("mgr")
                .rejectionReason("recount")
                .build();
    }

    private void found(CycleCountAdjustment adjustment) {
        when(adjustmentRepository.findById(ADJUSTMENT_ID)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any(CycleCountAdjustment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("another site's adjustment is refused before any state change")
        void otherSiteDenied() {
            CycleCountAdjustment adjustment = pending(SITE);
            found(adjustment);
            scopedApprover(OTHER_SITE);

            assertThatThrownBy(() -> service.approveAdjustment(
                            ADJUSTMENT_ID, ApproveAdjustmentRequest.builder().build(), null))
                    .isInstanceOf(LocationScopeDeniedException.class);

            assertThat(adjustment.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            verify(adjustmentRepository, never()).save(any());
            Mockito.verifyNoInteractions(ledgerPostingService);
        }

        @Test
        @DisplayName("a site the caller covers is approved and posted")
        void sameSiteAllowed() {
            found(pending(BIN));
            when(ledgerRepository.calculateOnHandQuantityAtLocation("SKU-1", BIN))
                    .thenReturn(new BigDecimal("10"));
            when(ledgerPostingService.post(any())).thenAnswer(inv -> {
                var entry = (com.positivity.inventory.internal.entity.InventoryLedgerEntry) inv.getArgument(0);
                entry.setLedgerEntryId(UUID.randomUUID());
                return entry;
            });
            scopedApprover(SITE);

            AdjustmentResponse response = service.approveAdjustment(
                    ADJUSTMENT_ID, ApproveAdjustmentRequest.builder().build(), null);

            assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        }

        @Test
        @DisplayName("a legacy adjustment with no location is denied to a scoped caller")
        void nullLocationDeniedWhenScoped() {
            CycleCountAdjustment adjustment = pending(null);
            found(adjustment);
            scopedApprover(SITE);

            assertThatThrownBy(() -> service.approveAdjustment(
                            ADJUSTMENT_ID, ApproveAdjustmentRequest.builder().build(), null))
                    .isInstanceOf(LocationScopeDeniedException.class);

            assertThat(adjustment.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            verify(adjustmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a legacy adjustment with no location is approved by a global caller")
        void nullLocationAllowedWhenGlobal() {
            found(pending(null));
            when(ledgerRepository.calculateOnHandQuantity("SKU-1")).thenReturn(new BigDecimal("10"));
            when(ledgerPostingService.post(any())).thenAnswer(inv -> {
                var entry = (com.positivity.inventory.internal.entity.InventoryLedgerEntry) inv.getArgument(0);
                entry.setLedgerEntryId(UUID.randomUUID());
                return entry;
            });
            globalApprover();

            AdjustmentResponse response = service.approveAdjustment(
                    ADJUSTMENT_ID, ApproveAdjustmentRequest.builder().build(), null);

            assertThat(response.getStatus()).isEqualTo(AdjustmentStatus.POSTED);
        }

        @Test
        @DisplayName("an unknown id is still a not-found for a scoped caller, so ids cannot be probed")
        void unknownIdIsNotFound() {
            when(adjustmentRepository.findById(ADJUSTMENT_ID)).thenReturn(Optional.empty());
            scopedApprover(OTHER_SITE);

            assertThatThrownBy(() -> service.approveAdjustment(
                            ADJUSTMENT_ID, ApproveAdjustmentRequest.builder().build(), null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("another site's adjustment is refused and left pending")
        void otherSiteDenied() {
            CycleCountAdjustment adjustment = pending(SITE);
            found(adjustment);
            scopedApprover(OTHER_SITE);

            assertThatThrownBy(() -> service.rejectAdjustment(ADJUSTMENT_ID, rejection()))
                    .isInstanceOf(LocationScopeDeniedException.class);

            assertThat(adjustment.getStatus()).isEqualTo(AdjustmentStatus.PENDING_APPROVAL);
            verify(adjustmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("a site the caller covers is rejected")
        void sameSiteAllowed() {
            found(pending(SITE));
            scopedApprover(SITE);

            assertThat(service.rejectAdjustment(ADJUSTMENT_ID, rejection()).getStatus())
                    .isEqualTo(AdjustmentStatus.REJECTED);
        }

        @Test
        @DisplayName("no location: denied when scoped, allowed when global")
        void nullLocation() {
            found(pending(null));
            scopedApprover(SITE);
            assertThatThrownBy(() -> service.rejectAdjustment(ADJUSTMENT_ID, rejection()))
                    .isInstanceOf(LocationScopeDeniedException.class);

            globalApprover();
            assertThat(service.rejectAdjustment(ADJUSTMENT_ID, rejection()).getStatus())
                    .isEqualTo(AdjustmentStatus.REJECTED);
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("another site's adjustment is refused, a covered site and a global caller read it")
        void gate() {
            found(pending(SITE));

            scopedApprover(OTHER_SITE);
            assertThatThrownBy(() -> service.getAdjustment(ADJUSTMENT_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);

            scopedApprover(SITE);
            assertThat(service.getAdjustment(ADJUSTMENT_ID).getAdjustmentId()).isEqualTo(ADJUSTMENT_ID);

            globalApprover();
            assertThat(service.getAdjustment(ADJUSTMENT_ID).getAdjustmentId()).isEqualTo(ADJUSTMENT_ID);
        }

        @Test
        @DisplayName("a view-only caller is gated on the view grant, not just approve")
        void viewAlternate() {
            found(pending(SITE));
            caller(Set.of(VIEW), Set.of(OTHER_SITE), VIEW);
            assertThatThrownBy(() -> service.getAdjustment(ADJUSTMENT_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);

            caller(Set.of(VIEW), Set.of(SITE), VIEW);
            assertThat(service.getAdjustment(ADJUSTMENT_ID).getAdjustmentId()).isEqualTo(ADJUSTMENT_ID);
        }

        @Test
        @DisplayName("no location: denied when scoped, readable when global")
        void nullLocation() {
            found(pending(null));
            scopedApprover(SITE);
            assertThatThrownBy(() -> service.getAdjustment(ADJUSTMENT_ID))
                    .isInstanceOf(LocationScopeDeniedException.class);

            globalApprover();
            assertThat(service.getAdjustment(ADJUSTMENT_ID).getAdjustmentId()).isEqualTo(ADJUSTMENT_ID);
        }
    }

    @Nested
    @DisplayName("lists and count")
    class Lists {

        @Test
        @DisplayName("a scoped caller's list and count go through a specification, never the unrestricted finders")
        void scopedIsNarrowed() {
            scopedApprover(SITE);
            when(adjustmentRepository.findAll(ArgumentMatchers.<Specification<CycleCountAdjustment>>any()))
                    .thenReturn(List.of(pending(BIN)));
            when(adjustmentRepository.count(ArgumentMatchers.<Specification<CycleCountAdjustment>>any()))
                    .thenReturn(1L);

            assertThat(service.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .hasSize(1);
            assertThat(service.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .isEqualTo(1L);

            verify(adjustmentRepository, never()).findByStatus(any());
            verify(adjustmentRepository, never()).countByStatus(any());
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("the specification restricts to the status and the caller's reach, sites and their bins")
        void specificationCarriesTheReach() {
            scopedApprover(SITE);
            when(adjustmentRepository.findAll(ArgumentMatchers.<Specification<CycleCountAdjustment>>any()))
                    .thenReturn(List.of());

            service.listAdjustmentsByStatus(AdjustmentStatus.APPROVED);

            ArgumentCaptor<Specification<CycleCountAdjustment>> captor = ArgumentCaptor.forClass(Specification.class);
            verify(adjustmentRepository).findAll(captor.capture());

            Root<CycleCountAdjustment> root = mock(Root.class, Mockito.RETURNS_DEEP_STUBS);
            CriteriaQuery<?> query = mock(CriteriaQuery.class, Mockito.RETURNS_DEEP_STUBS);
            CriteriaBuilder cb = mock(CriteriaBuilder.class, Mockito.RETURNS_DEEP_STUBS);
            captor.getValue().toPredicate(root, query, cb);

            verify(cb).equal(root.get("status"), AdjustmentStatus.APPROVED);
            verify(root.get("locationId"), Mockito.atLeastOnce()).in(Set.of(SITE, BIN));
            verify(query).subquery(UUID.class);
            verify(query.subquery(UUID.class)).from(ExtStorageLocationReplica.class);
        }

        @Test
        @DisplayName("a scoped caller with an empty reach sees nothing and the database is never asked")
        void emptyReach() {
            caller(Set.of(APPROVE, VIEW), Set.of(UUID.randomUUID()), APPROVE);

            assertThat(service.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .isEmpty();
            assertThat(service.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .isZero();
            verifyNoMoreInteractions(adjustmentRepository);
        }

        @Test
        @DisplayName("a global caller is not narrowed and still sees location-less legacy rows")
        void globalIsUnrestricted() {
            globalApprover();
            when(adjustmentRepository.findByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .thenReturn(List.of(pending(null), pending(SITE)));
            when(adjustmentRepository.countByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .thenReturn(2L);

            assertThat(service.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .hasSize(2);
            assertThat(service.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                    .isEqualTo(2L);
            verify(adjustmentRepository, never()).findAll(ArgumentMatchers.<Specification<CycleCountAdjustment>>any());
            verify(adjustmentRepository, never()).count(ArgumentMatchers.<Specification<CycleCountAdjustment>>any());
        }
    }
}
