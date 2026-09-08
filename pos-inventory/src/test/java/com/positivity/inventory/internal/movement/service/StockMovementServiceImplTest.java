package com.positivity.inventory.internal.movement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.InventoryLedgerEntryResponse;
import com.positivity.inventory.internal.dto.RecordMovementRequest;
import com.positivity.inventory.internal.entity.InventoryAdjustmentRequest;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.enums.AdjustmentRequestStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.MovementType;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryAdjustmentRequestRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.service.LedgerPostingService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class StockMovementServiceImplTest {

    private static final UUID LOC_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID LOC_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LOC_9 = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /** The site that owns LOC_1 in the replica: assigning it puts LOC_1 in reach. */
    private static final UUID SITE_OF_LOC_1 = UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa");
    /** A site elsewhere in the tree: assigning it leaves LOC_1 out of reach. */
    private static final UUID OTHER_SITE = UUID.fromString("bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb");

    /** Replica stand-in: LOC_1 sits under SITE_OF_LOC_1 on both dimensions; nothing else is known. */
    private static final LocationAncestorResolver RESOLVER = locationId -> LOC_1.equals(locationId)
            ? new AncestorSets(Set.of(LOC_1, SITE_OF_LOC_1), Set.of(LOC_1, SITE_OF_LOC_1))
            : AncestorSets.EMPTY;

    /** The grants INVENTORY_MANAGER and INVENTORY_CONTROLLER share (#1373). */
    private static final List<SimpleGrantedAuthority> ADJUSTMENT_AUTHORITIES = List.of(
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_CREATE),
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_APPROVE),
            new SimpleGrantedAuthority(InventoryPermissionRegistry.ADJUSTMENT_VIEW));

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private InventoryAdjustmentRequestRepository adjustmentRepository;

    @Mock
    private LocationRefRepository locationRefRepository;

    @Mock
    private ExtStorageLocationReplicaRepository storageLocationRepository;

    @Mock
    private Clock clock;

    private StockMovementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StockMovementServiceImpl(
                ledgerRepository,
                adjustmentRepository,
                ledgerPostingService,
                locationRefRepository,
                storageLocationRepository,
                new com.positivity.inventory.internal.service.QuantityScaleGuard(
                        org.mockito.Mockito.mock(com.positivity.inventory.internal.service.UomConversionService.class)),
                clock);
        // Default caller: a pre-rollout token (no loc_* claims), which ADR-0061 treats as unscoped
        // so the existing approve expectations are unchanged.
        authenticate("approver-1", LocationScope.unscoped());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String username, LocationScope scope) {
        var authentication = new UsernamePasswordAuthenticationToken(username, null, ADJUSTMENT_AUTHORITIES);
        authentication.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME, username,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, scope));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** A caller whose ADJUSTMENT_APPROVE is scoped (OTHER dimension) to the given assigned nodes. */
    private static LocationScope approveScopedTo(LocationAncestorResolver resolver, UUID... nodes) {
        return LocationScope.of(
                Set.of(),
                Set.of(InventoryPermissionRegistry.ADJUSTMENT_APPROVE),
                Optional.of(Set.of(nodes)),
                true,
                resolver);
    }

    /** A post-rollout caller whose grants are all global: claims present, no permission in either bitset. */
    private static LocationScope globalReach(UUID... nodes) {
        return LocationScope.of(Set.of(), Set.of(), Optional.of(Set.of(nodes)), true, RESOLVER);
    }

    private void setupClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-02-26T00:00:00Z"));
    }

    @Test
    void recordMovement_receive_setsGoodsReceipt_positiveDelta_andSavesEntry() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RECEIVE, 5);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("10"));

        InventoryLedgerEntryResponse result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_RECEIPT);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("5");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_pick_withSufficientStock_setsGoodsIssue_negativeDelta() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.PICK, 4);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("10"));

        InventoryLedgerEntryResponse result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_ISSUE);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("-4");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_pick_withInsufficientStock_throwsInsufficientStockException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.PICK, 5);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("3"));

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception).isInstanceOf(InsufficientStockException.class);
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_issue_withInsufficientStock_throwsInsufficientStockException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.ISSUE, 7);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("2"));

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception).isInstanceOf(InsufficientStockException.class);
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_adjust_throwsIllegalArgumentException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.ADJUST, 1);

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADJUST must use adjustment workflow");
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_pick_usesSourceLocationOnHand_notGlobalOnHand() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.PICK, 5);
        request.setFromLocationId(LOC_2);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_2))
                .thenReturn(new BigDecimal("8"));

        InventoryLedgerEntryResponse result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.GOODS_ISSUE);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("-5");
        verify(ledgerRepository, times(2)).calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_2);
        verify(ledgerRepository, never()).calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1);
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_transfer_savesTransferInAndTransferOut_withLocations() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.TRANSFER, 6);
        request.setToLocationId(LOC_2);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_2))
                .thenReturn(new BigDecimal("20"));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1))
                .thenReturn(new BigDecimal("20"));

        service.recordMovement(request, "actor-1");

        ArgumentCaptor<InventoryLedgerEntry> captor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService, times(2)).post(captor.capture());
        List<InventoryLedgerEntry> savedEntries = captor.getAllValues();

        InventoryLedgerEntry transferIn = savedEntries.get(0);
        assertThat(transferIn.getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_IN);
        assertThat(transferIn.getLocationId()).isEqualTo(LOC_2);
        assertThat(transferIn.getFromLocationId()).isEqualTo(LOC_1);
        assertThat(transferIn.getToLocationId()).isEqualTo(LOC_2);
        assertThat(transferIn.getChangeInQuantity()).isEqualByComparingTo("6");

        InventoryLedgerEntry transferOut = savedEntries.get(1);
        assertThat(transferOut.getEventType()).isEqualTo(InventoryLedgerEventType.TRANSFER_OUT);
        assertThat(transferOut.getLocationId()).isEqualTo(LOC_1);
        assertThat(transferOut.getFromLocationId()).isEqualTo(LOC_1);
        assertThat(transferOut.getToLocationId()).isEqualTo(LOC_2);
        assertThat(transferOut.getChangeInQuantity()).isEqualByComparingTo("-6");
        verify(ledgerRepository).calculateOnHandQuantityAtLocation("SKU-123", LOC_2);
        verify(ledgerRepository).calculateOnHandQuantityAtLocation("SKU-123", LOC_1);
    }

    @Test
    void recordMovement_transfer_withoutToLocation_throwsIllegalArgumentException() {
        RecordMovementRequest request = baseMovementRequest(MovementType.TRANSFER, 6);
        request.setToLocationId(null);

        Throwable exception = catchThrowable(() -> service.recordMovement(request, "actor-1"));

        assertThat(exception)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toLocationId is required for TRANSFER movements");
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_putAway_setsPutaway_positiveDelta() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.PUT_AWAY, 3);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("8"));

        InventoryLedgerEntryResponse result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.PUTAWAY);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("3");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_return_setsReturnToStock_positiveDelta() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RETURN, 2);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("5"));

        InventoryLedgerEntryResponse result = service.recordMovement(request, "actor-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.RETURN_TO_STOCK);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("2");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void recordMovement_storesActorUserIdInTransactionUserId() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        RecordMovementRequest request = baseMovementRequest(MovementType.RECEIVE, 1);
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getFromLocationId()))
                .thenReturn(new BigDecimal("0"));

        service.recordMovement(request, "actor-xyz");

        ArgumentCaptor<InventoryLedgerEntry> captor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(captor.capture());
        assertThat(captor.getValue().getTransactionUserId()).isEqualTo("actor-xyz");
    }

    @Test
    void createAdjustmentRequest_happyPath_savesPendingRequest_withRequestedByActor() {
        setupClock();
        stubAdjustmentSaveReturnsRequest();
        CreateAdjustmentRequestDto request = baseAdjustmentRequest(7);

        AdjustmentRequestResponse result = service.createAdjustmentRequest(request, "requestor-1");

        assertThat(result.getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING.name());

        ArgumentCaptor<InventoryAdjustmentRequest> captor = ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
        verify(adjustmentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING);
        assertThat(captor.getValue().getRequestedByUserId()).isEqualTo("requestor-1");
    }

    @Test
    void createAdjustmentRequest_returnsRequestMatchingInputFields() {
        setupClock();
        stubAdjustmentSaveReturnsRequest();
        CreateAdjustmentRequestDto request = baseAdjustmentRequest(11);

        AdjustmentRequestResponse result = service.createAdjustmentRequest(request, "requestor-1");

        assertThat(result.getProductSku()).isEqualTo("SKU-123");
        assertThat(result.getLocationId()).isEqualTo(LOC_1);
        assertThat(result.getQuantity()).isEqualByComparingTo("11");
        assertThat(result.getReasonCode()).isEqualTo("CYCLE_COUNT");
    }

    @Test
    void approveAdjustmentRequest_positiveQuantity_savesAdjustmentInLedgerEntry() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(4);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getLocationId()))
                .thenReturn(new BigDecimal("15"));

        InventoryLedgerEntryResponse result =
                service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_IN);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("4");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_negativeQuantity_savesAdjustmentOutLedgerEntry() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(-3);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getLocationId()))
                .thenReturn(new BigDecimal("10"));

        InventoryLedgerEntryResponse result =
                service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1");

        assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_OUT);
        assertThat(result.getChangeInQuantity()).isEqualByComparingTo("-3");
        verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_whenRequestNotFound_throwsIllegalArgumentException() {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(adjustmentRepository.findById(requestId)).thenReturn(Optional.empty());

        Throwable exception = catchThrowable(() -> service.approveAdjustmentRequest(requestId, "approver-1"));

        assertThat(exception).isInstanceOf(IllegalArgumentException.class);
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_whenNotPending_throwsIllegalStateException() {
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(2);
        request.setStatus(AdjustmentRequestStatus.APPROVED);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));

        Throwable exception =
                catchThrowable(() -> service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-1"));

        assertThat(exception).isInstanceOf(IllegalStateException.class);
        verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
    }

    @Test
    void approveAdjustmentRequest_setsApprovedStatusAndApproverUserId() {
        setupClock();
        stubLedgerSaveReturnsEntry();
        stubAdjustmentSaveReturnsRequest();
        InventoryAdjustmentRequest request = pendingAdjustmentRequest(5);
        when(adjustmentRepository.findById(request.getAdjustmentRequestId())).thenReturn(Optional.of(request));
        when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), request.getLocationId()))
                .thenReturn(new BigDecimal("20"));

        service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "approver-xyz");

        ArgumentCaptor<InventoryAdjustmentRequest> adjustmentCaptor =
                ArgumentCaptor.forClass(InventoryAdjustmentRequest.class);
        verify(adjustmentRepository).save(adjustmentCaptor.capture());

        InventoryAdjustmentRequest savedRequest = adjustmentCaptor.getValue();
        assertThat(savedRequest.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
        assertThat(savedRequest.getApprovedByUserId()).isEqualTo("approver-xyz");
    }

    @Nested
    @DisplayName("approveAdjustmentRequest location scope (ADR-0061 §3, #1871)")
    class ApproveLocationScope {

        @Test
        @DisplayName("request location in reach: posts the ledger entry and approves")
        void inReach_postsAndApproves() {
            authenticate("scoped-approver", approveScopedTo(RESOLVER, SITE_OF_LOC_1));
            setupClock();
            stubLedgerSaveReturnsEntry();
            stubAdjustmentSaveReturnsRequest();
            InventoryAdjustmentRequest request = pendingAdjustmentRequest(4);
            when(adjustmentRepository.findById(request.getAdjustmentRequestId()))
                    .thenReturn(Optional.of(request));
            when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1))
                    .thenReturn(new BigDecimal("15"));

            InventoryLedgerEntryResponse result =
                    service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "scoped-approver");

            assertThat(result.getEventType()).isEqualTo(InventoryLedgerEventType.ADJUSTMENT_IN);
            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
            verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
        }

        @Test
        @DisplayName("request location out of reach: LocationScopeDeniedException, nothing posted, still PENDING")
        void outOfReach_deniesBeforeAnyStateChange() {
            authenticate("scoped-approver", approveScopedTo(RESOLVER, OTHER_SITE));
            InventoryAdjustmentRequest request = pendingAdjustmentRequest(4);
            when(adjustmentRepository.findById(request.getAdjustmentRequestId()))
                    .thenReturn(Optional.of(request));

            Throwable exception = catchThrowable(
                    () -> service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "scoped-approver"));

            assertThat(exception)
                    .isInstanceOf(LocationScopeDeniedException.class)
                    .asInstanceOf(
                            org.assertj.core.api.InstanceOfAssertFactories.type(LocationScopeDeniedException.class))
                    .satisfies(denied -> {
                        assertThat(denied.permission()).isEqualTo(InventoryPermissionRegistry.ADJUSTMENT_APPROVE);
                        assertThat(denied.locationId()).isEqualTo(LOC_1.toString());
                    });
            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING);
            assertThat(request.getApprovedByUserId()).isNull();
            verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
            verify(adjustmentRepository, never()).save(any(InventoryAdjustmentRequest.class));
            verifyNoInteractions(ledgerRepository);
        }

        @Test
        @DisplayName("missing request: not-found rejection first; the scope is never consulted")
        void missingRequest_rejectsBeforeScopeCheck() {
            LocationAncestorResolver resolver = mock(LocationAncestorResolver.class);
            authenticate("scoped-approver", approveScopedTo(resolver, OTHER_SITE));
            UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            when(adjustmentRepository.findById(requestId)).thenReturn(Optional.empty());

            Throwable exception = catchThrowable(() -> service.approveAdjustmentRequest(requestId, "scoped-approver"));

            assertThat(exception).isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(resolver);
            verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));
        }

        @Test
        @DisplayName("pre-rollout token (no loc_* claims): approval is unchanged even when out of reach")
        void preRolloutToken_isUnchanged() {
            authenticate("legacy-approver", LocationScope.unscoped());
            setupClock();
            stubLedgerSaveReturnsEntry();
            stubAdjustmentSaveReturnsRequest();
            InventoryAdjustmentRequest request = pendingAdjustmentRequest(2);
            when(adjustmentRepository.findById(request.getAdjustmentRequestId()))
                    .thenReturn(Optional.of(request));
            when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1))
                    .thenReturn(new BigDecimal("1"));

            service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "legacy-approver");

            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
            verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
        }

        @Test
        @DisplayName("ALL-scoped caller (permission in neither bitset): approval is unchanged")
        void globalCaller_isUnchanged() {
            authenticate("controller", globalReach(OTHER_SITE));
            setupClock();
            stubLedgerSaveReturnsEntry();
            stubAdjustmentSaveReturnsRequest();
            InventoryAdjustmentRequest request = pendingAdjustmentRequest(2);
            when(adjustmentRepository.findById(request.getAdjustmentRequestId()))
                    .thenReturn(Optional.of(request));
            when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1))
                    .thenReturn(new BigDecimal("1"));

            service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "controller");

            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
            verify(ledgerPostingService).post(any(InventoryLedgerEntry.class));
        }

        @Test
        @DisplayName(
                "#1373 pair: identical grants, same out-of-reach request; LOCATION scope denies, ALL scope approves")
        void identicalGrants_scopeIsTheOnlyDifference() {
            InventoryAdjustmentRequest request = pendingAdjustmentRequest(3);
            when(adjustmentRepository.findById(request.getAdjustmentRequestId()))
                    .thenReturn(Optional.of(request));

            // INVENTORY_MANAGER: same authorities, ADJUSTMENT_APPROVE carried in a scope bitset,
            // assigned to a site that is not above LOC_1.
            authenticate("inventory-manager", approveScopedTo(RESOLVER, OTHER_SITE));
            Throwable denied = catchThrowable(
                    () -> service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "inventory-manager"));
            assertThat(denied).isInstanceOf(LocationScopeDeniedException.class);
            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.PENDING);
            verify(ledgerPostingService, never()).post(any(InventoryLedgerEntry.class));

            // INVENTORY_CONTROLLER: same authorities, same assigned node, but ADJUSTMENT_APPROVE is
            // in neither bitset, so the grant is global.
            authenticate("inventory-controller", globalReach(OTHER_SITE));
            setupClock();
            stubLedgerSaveReturnsEntry();
            stubAdjustmentSaveReturnsRequest();
            when(ledgerRepository.calculateOnHandQuantityAtLocation(request.getProductSku(), LOC_1))
                    .thenReturn(new BigDecimal("7"));

            service.approveAdjustmentRequest(request.getAdjustmentRequestId(), "inventory-controller");

            assertThat(request.getStatus()).isEqualTo(AdjustmentRequestStatus.APPROVED);
            assertThat(request.getApprovedByUserId()).isEqualTo("inventory-controller");
            verify(ledgerPostingService, times(1)).post(any(InventoryLedgerEntry.class));
        }
    }

    private RecordMovementRequest baseMovementRequest(MovementType movementType, int quantity) {
        return RecordMovementRequest.builder()
                .productSku("SKU-123")
                .fromLocationId(LOC_1)
                .toLocationId(LOC_9)
                .movementType(movementType)
                .quantity(BigDecimal.valueOf(quantity))
                .unitOfMeasure("EACH")
                .sourceTransactionId("TX-1")
                .build();
    }

    private CreateAdjustmentRequestDto baseAdjustmentRequest(int quantity) {
        return CreateAdjustmentRequestDto.builder()
                .productSku("SKU-123")
                .locationId(LOC_1)
                .quantity(BigDecimal.valueOf(quantity))
                .reasonCode("CYCLE_COUNT")
                .unitOfMeasure("EACH")
                .build();
    }

    private InventoryAdjustmentRequest pendingAdjustmentRequest(int quantity) {
        return InventoryAdjustmentRequest.builder()
                .adjustmentRequestId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .productSku("SKU-123")
                .locationId(LOC_1)
                .quantity(BigDecimal.valueOf(quantity))
                .reasonCode("CYCLE_COUNT")
                .unitOfMeasure("EACH")
                .status(AdjustmentRequestStatus.PENDING)
                .requestedByUserId("requestor-1")
                .build();
    }

    private void stubLedgerSaveReturnsEntry() {
        when(ledgerPostingService.post(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubAdjustmentSaveReturnsRequest() {
        when(adjustmentRepository.save(any(InventoryAdjustmentRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
