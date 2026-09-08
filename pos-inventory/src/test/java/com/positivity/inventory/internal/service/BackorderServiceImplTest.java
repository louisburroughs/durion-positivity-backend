package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.BackorderCreatedV1;
import com.positivity.domainevents.inventory.BackorderResolvedV1;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.entity.BackorderRecord;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.BackorderResolutionSource;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.BackorderRecordRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.reservation.service.BackorderServiceImpl;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for {@link BackorderServiceImpl} (odoo-parity G1, issue #1046): backorder creation
 * (OPEN + BACKORDER_CREATED + fact, idempotent), and availability-driven auto-resolution
 * (oldest-first, whole-backorder-only coverage, idempotent, CANCELLED never resolves).
 */
@ExtendWith(MockitoExtension.class)
class BackorderServiceImplTest {

    private static final String SKU = "PART-BRAKE-PAD-01";
    private static final UUID LOCATION_ID = UUID.fromString("01960004-0001-7000-8000-000000000001");
    private static final UUID WORKORDER_LINE_A = UUID.fromString("01960004-0002-7000-8000-00000000000a");
    private static final UUID WORKORDER_LINE_B = UUID.fromString("01960004-0002-7000-8000-00000000000b");

    @Mock
    private BackorderRecordRepository backorderRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryLedgerEntryRepository ledgerRepository;

    @Mock
    private InventoryStockSummaryRepository summaryRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    @Mock
    private BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Mock
    private LocationScopeService locationScopeService;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);

    private BackorderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BackorderServiceImpl(
                backorderRepository,
                reservationRepository,
                ledgerRepository,
                summaryRepository,
                ledgerPostingService,
                inventoryFactPublisher,
                fixedClock,
                baseUnitOfMeasureResolver,
                locationScopeService);
        lenient().when(backorderRepository.save(any(BackorderRecord.class))).thenAnswer(invocation -> {
            BackorderRecord record = invocation.getArgument(0);
            if (record.getBackorderId() == null) {
                record.setBackorderId(UUID.randomUUID());
            }
            if (record.getCreatedAt() == null) {
                record.setCreatedAt(Instant.now(fixedClock));
            }
            return record;
        });
        lenient()
                .when(ledgerPostingService.post(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                });
        lenient()
                .when(ledgerRepository.calculateOnHandQuantityAtLocation(SKU, LOCATION_ID))
                .thenReturn(new BigDecimal("0"));
    }

    @Test
    @DisplayName("createBackorder opens OPEN record, posts ATP-neutral BACKORDER_CREATED, emits fact")
    void createBackorder_opensRecordPostsLedgerAndFact() {
        when(backorderRepository.findByWorkorderLineIdAndSkuAndStatus(WORKORDER_LINE_A, SKU, BackorderStatus.OPEN))
                .thenReturn(Optional.empty());

        BackorderResponse response = service.createBackorder(WORKORDER_LINE_A, SKU, new BigDecimal("5"), LOCATION_ID);

        assertThat(response.getStatus()).isEqualTo(BackorderStatus.OPEN);
        assertThat(response.getQuantityShort()).isEqualByComparingTo("5");
        assertThat(response.getSku()).isEqualTo(SKU);
        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);

        ArgumentCaptor<InventoryLedgerEntry> entryCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(entryCaptor.capture());
        InventoryLedgerEntry entry = entryCaptor.getValue();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.BACKORDER_CREATED);
        assertThat(entry.getEventType().affectsOnHand()).isFalse();
        assertThat(entry.getChangeInQuantity()).isEqualByComparingTo("5");
        assertThat(entry.getStockItemId()).isEqualTo(SKU);
        assertThat(entry.getSourceTransactionId())
                .isEqualTo(response.getBackorderId().toString());

        ArgumentCaptor<BackorderCreatedV1> factCaptor = ArgumentCaptor.forClass(BackorderCreatedV1.class);
        verify(inventoryFactPublisher).recordBackorderCreated(factCaptor.capture());
        assertThat(factCaptor.getValue().backorderId()).isEqualTo(response.getBackorderId());
        assertThat(factCaptor.getValue().quantityShort()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("createBackorder is idempotent: an existing OPEN backorder is returned, not duplicated")
    void createBackorder_existingOpen_returnsExisting() {
        BackorderRecord existing = openBackorder(WORKORDER_LINE_A, 5, Instant.parse("2026-07-22T00:00:00Z"));
        when(backorderRepository.findByWorkorderLineIdAndSkuAndStatus(WORKORDER_LINE_A, SKU, BackorderStatus.OPEN))
                .thenReturn(Optional.of(existing));

        BackorderResponse response = service.createBackorder(WORKORDER_LINE_A, SKU, new BigDecimal("5"), LOCATION_ID);

        assertThat(response.getBackorderId()).isEqualTo(existing.getBackorderId());
        verify(backorderRepository, never()).save(any());
        verifyNoInteractions(ledgerPostingService);
        verify(inventoryFactPublisher, never()).recordBackorderCreated(any());
    }

    @Test
    @DisplayName("createBackorderForSalesOrderLine opens OPEN record keyed by salesOrderLineId")
    void createBackorderForSalesOrderLine_opensRecordPostsLedgerAndFact() {
        UUID salesOrderLineId = UUID.fromString("01960004-0003-7000-8000-000000000001");
        when(backorderRepository.findBySalesOrderLineIdAndSkuAndStatus(salesOrderLineId, SKU, BackorderStatus.OPEN))
                .thenReturn(Optional.empty());

        BackorderResponse response =
                service.createBackorderForSalesOrderLine(salesOrderLineId, SKU, new BigDecimal("4"), LOCATION_ID);

        assertThat(response.getStatus()).isEqualTo(BackorderStatus.OPEN);
        assertThat(response.getSalesOrderLineId()).isEqualTo(salesOrderLineId);
        assertThat(response.getWorkorderLineId()).isNull();
        assertThat(response.getQuantityShort()).isEqualByComparingTo("4");

        ArgumentCaptor<BackorderCreatedV1> factCaptor = ArgumentCaptor.forClass(BackorderCreatedV1.class);
        verify(inventoryFactPublisher).recordBackorderCreated(factCaptor.capture());
        assertThat(factCaptor.getValue().salesOrderLineId()).isEqualTo(salesOrderLineId);
        assertThat(factCaptor.getValue().workorderLineId()).isNull();
    }

    @Test
    @DisplayName("Stock arrival auto-resolves the older backorder first when only one can be covered")
    void onInboundAvailability_resolvesOldestFirst_whenBudgetCoversOne() {
        BackorderRecord older = openBackorder(WORKORDER_LINE_A, 5, Instant.parse("2026-07-20T00:00:00Z"));
        BackorderRecord newer = openBackorder(WORKORDER_LINE_B, 5, Instant.parse("2026-07-21T00:00:00Z"));
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of(older, newer));
        when(reservationRepository.findByWorkorderLineIdOrSalesOrderLineId(any(), any()))
                .thenReturn(Optional.empty());
        // Budget covers exactly one backorder of 5.
        when(summaryRepository.findByStockItemIdAndLocationId(SKU, LOCATION_ID))
                .thenReturn(Optional.of(summaryWithAtp(5)));

        service.onInboundAvailability(SKU, LOCATION_ID);

        assertThat(older.getStatus()).isEqualTo(BackorderStatus.RESOLVED);
        assertThat(older.getResolutionSource()).isEqualTo(BackorderResolutionSource.AVAILABILITY);
        assertThat(newer.getStatus()).isEqualTo(BackorderStatus.OPEN);

        ArgumentCaptor<InventoryLedgerEntry> entryCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(entryCaptor.capture());
        assertThat(entryCaptor.getValue().getEventType()).isEqualTo(InventoryLedgerEventType.BACKORDER_RESOLVED);
        assertThat(entryCaptor.getValue().getSourceTransactionId())
                .isEqualTo(older.getBackorderId().toString());

        ArgumentCaptor<BackorderResolvedV1> factCaptor = ArgumentCaptor.forClass(BackorderResolvedV1.class);
        verify(inventoryFactPublisher, times(1)).recordBackorderResolved(factCaptor.capture());
        assertThat(factCaptor.getValue().backorderId()).isEqualTo(older.getBackorderId());
        assertThat(factCaptor.getValue().resolutionSource()).isEqualTo("AVAILABILITY");
    }

    @Test
    @DisplayName("Resolution re-opens a BACKORDERED backing reservation to PENDING")
    void onInboundAvailability_reopensBackingReservation() {
        BackorderRecord backorder = openBackorder(WORKORDER_LINE_A, 5, Instant.parse("2026-07-20T00:00:00Z"));
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of(backorder));
        ReservationEntity reservation = ReservationEntity.builder()
                .workorderLineId(WORKORDER_LINE_A)
                .stockItemId(UUID.randomUUID())
                .requiredQuantity(new BigDecimal("5"))
                .priority(5)
                .status(ReservationStatus.BACKORDERED)
                .build();
        when(reservationRepository.findByWorkorderLineIdOrSalesOrderLineId(WORKORDER_LINE_A, WORKORDER_LINE_A))
                .thenReturn(Optional.of(reservation));
        when(summaryRepository.findByStockItemIdAndLocationId(SKU, LOCATION_ID))
                .thenReturn(Optional.of(summaryWithAtp(10)));

        service.onInboundAvailability(SKU, LOCATION_ID);

        assertThat(backorder.getStatus()).isEqualTo(BackorderStatus.RESOLVED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
        verify(reservationRepository).save(reservation);
    }

    @Test
    @DisplayName("Replayed availability signal does not re-resolve an already-resolved backorder")
    void onInboundAvailability_replay_isIdempotent() {
        // After the first resolution the candidate query returns no OPEN backorders.
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of());

        service.onInboundAvailability(SKU, LOCATION_ID);

        verifyNoInteractions(ledgerPostingService);
        verify(inventoryFactPublisher, never()).recordBackorderResolved(any());
    }

    @Test
    @DisplayName("Partial availability leaves a backorder larger than the budget OPEN")
    void onInboundAvailability_partialAvailability_leavesLargerOpen() {
        BackorderRecord backorder = openBackorder(WORKORDER_LINE_A, 10, Instant.parse("2026-07-20T00:00:00Z"));
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of(backorder));
        when(reservationRepository.findByWorkorderLineIdOrSalesOrderLineId(any(), any()))
                .thenReturn(Optional.empty());
        when(summaryRepository.findByStockItemIdAndLocationId(SKU, LOCATION_ID))
                .thenReturn(Optional.of(summaryWithAtp(4)));

        service.onInboundAvailability(SKU, LOCATION_ID);

        assertThat(backorder.getStatus()).isEqualTo(BackorderStatus.OPEN);
        verifyNoInteractions(ledgerPostingService);
        verify(inventoryFactPublisher, never()).recordBackorderResolved(any());
    }

    @Test
    @DisplayName("A CANCELLED backorder is never an auto-resolution candidate (only OPEN is scanned)")
    void onInboundAvailability_cancelledNeverResolves() {
        // The candidate query filters status=OPEN, so a CANCELLED backorder is simply never returned.
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of());

        service.onInboundAvailability(SKU, LOCATION_ID);

        verify(backorderRepository)
                .findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(SKU, LOCATION_ID, BackorderStatus.OPEN);
        verifyNoInteractions(ledgerPostingService);
    }

    @Test
    @DisplayName("A null location key is ignored by the resolution trigger")
    void onInboundAvailability_nullLocation_noOp() {
        service.onInboundAvailability(SKU, null);
        verifyNoInteractions(backorderRepository);
        verifyNoInteractions(ledgerPostingService);
    }

    private BackorderRecord openBackorder(UUID workorderLineId, int quantityShort, Instant createdAt) {
        return BackorderRecord.builder()
                .backorderId(UUID.randomUUID())
                .workorderLineId(workorderLineId)
                .sku(SKU)
                .locationId(LOCATION_ID)
                .quantityShort(BigDecimal.valueOf(quantityShort))
                .status(BackorderStatus.OPEN)
                .createdBy("SYSTEM")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build();
    }

    private InventoryStockSummary summaryWithAtp(long atp) {
        return InventoryStockSummary.builder()
                .stockItemId(SKU)
                .locationId(LOCATION_ID)
                .onHand(BigDecimal.valueOf(atp))
                .allocated(new BigDecimal("0"))
                .atp(BigDecimal.valueOf(atp))
                .build();
    }

    // ─── ADR-0061 §3 (#1872): location scope on the read side ────────────────

    @Test
    @DisplayName("listBackorders without a site filter narrows a scoped caller to their reach")
    @SuppressWarnings("unchecked")
    void listBackorders_scopedNoFilter_narrowsToReach() {
        when(locationScopeService.narrowTo(isNull(), eq(InventoryPermissionRegistry.SHORTAGE_VIEW)))
                .thenReturn(Optional.of(Set.of(LOCATION_ID)));
        when(backorderRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(openBackorder(WORKORDER_LINE_A, 5, Instant.now(fixedClock))));

        List<BackorderResponse> result = service.listBackorders(null, null, null, null, null);

        assertThat(result).hasSize(1);
        verify(backorderRepository).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    @DisplayName("listBackorders with an empty reach answers an empty list without querying")
    void listBackorders_scopedEmptyReach_returnsEmptyWithoutQuery() {
        when(locationScopeService.narrowTo(isNull(), eq(InventoryPermissionRegistry.SHORTAGE_VIEW)))
                .thenReturn(Optional.of(Set.of()));

        assertThat(service.listBackorders(null, null, null, null, null)).isEmpty();
        verifyNoInteractions(backorderRepository);
    }

    @Test
    @DisplayName("listBackorders with a site filter gates the filter and queries it as before")
    @SuppressWarnings("unchecked")
    void listBackorders_filter_gatesThenQueries() {
        when(backorderRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of());

        service.listBackorders(null, null, LOCATION_ID, null, null);

        verify(locationScopeService).narrowTo(LOCATION_ID, InventoryPermissionRegistry.SHORTAGE_VIEW);
        verify(backorderRepository).findAll(any(Specification.class), any(Sort.class));
    }

    @Test
    @DisplayName("listBackorders propagates a denied site filter without querying")
    void listBackorders_deniedFilter_propagates() {
        when(locationScopeService.narrowTo(eq(LOCATION_ID), eq(InventoryPermissionRegistry.SHORTAGE_VIEW)))
                .thenThrow(new LocationScopeDeniedException(
                        InventoryPermissionRegistry.SHORTAGE_VIEW, LOCATION_ID.toString()));

        assertThatThrownBy(() -> service.listBackorders(null, null, LOCATION_ID, null, null))
                .isInstanceOf(LocationScopeDeniedException.class);
        verifyNoInteractions(backorderRepository);
    }

    @Test
    @DisplayName("getBackorder gates on the loaded record's site, after the 404")
    void getBackorder_gatesOnRecordSiteAfterLoad() {
        BackorderRecord record = openBackorder(WORKORDER_LINE_A, 5, Instant.now(fixedClock));
        when(backorderRepository.findById(record.getBackorderId())).thenReturn(Optional.of(record));
        doThrow(new LocationScopeDeniedException(InventoryPermissionRegistry.SHORTAGE_VIEW, LOCATION_ID.toString()))
                .when(locationScopeService)
                .require(LOCATION_ID, InventoryPermissionRegistry.SHORTAGE_VIEW);

        assertThatThrownBy(() -> service.getBackorder(record.getBackorderId()))
                .isInstanceOf(LocationScopeDeniedException.class);
    }

    @Test
    @DisplayName("getBackorder answers 404 before any scope check for an unknown id")
    void getBackorder_unknownId_notFoundBeforeScope() {
        UUID missing = UUID.randomUUID();
        when(backorderRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBackorder(missing)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(locationScopeService);
    }
}
