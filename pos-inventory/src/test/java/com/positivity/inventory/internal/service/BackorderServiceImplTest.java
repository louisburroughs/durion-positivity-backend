package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.positivity.inventory.internal.repository.BackorderRecordRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                fixedClock);
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
                .thenReturn(0);
    }

    @Test
    @DisplayName("createBackorder opens OPEN record, posts ATP-neutral BACKORDER_CREATED, emits fact")
    void createBackorder_opensRecordPostsLedgerAndFact() {
        when(backorderRepository.findByWorkorderLineIdAndSkuAndStatus(WORKORDER_LINE_A, SKU, BackorderStatus.OPEN))
                .thenReturn(Optional.empty());

        BackorderResponse response = service.createBackorder(WORKORDER_LINE_A, SKU, 5, LOCATION_ID);

        assertThat(response.getStatus()).isEqualTo(BackorderStatus.OPEN);
        assertThat(response.getQuantityShort()).isEqualTo(5);
        assertThat(response.getSku()).isEqualTo(SKU);
        assertThat(response.getLocationId()).isEqualTo(LOCATION_ID);

        ArgumentCaptor<InventoryLedgerEntry> entryCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(ledgerPostingService).post(entryCaptor.capture());
        InventoryLedgerEntry entry = entryCaptor.getValue();
        assertThat(entry.getEventType()).isEqualTo(InventoryLedgerEventType.BACKORDER_CREATED);
        assertThat(entry.getEventType().affectsOnHand()).isFalse();
        assertThat(entry.getChangeInQuantity()).isEqualTo(5);
        assertThat(entry.getStockItemId()).isEqualTo(SKU);
        assertThat(entry.getSourceTransactionId())
                .isEqualTo(response.getBackorderId().toString());

        ArgumentCaptor<BackorderCreatedV1> factCaptor = ArgumentCaptor.forClass(BackorderCreatedV1.class);
        verify(inventoryFactPublisher).recordBackorderCreated(factCaptor.capture());
        assertThat(factCaptor.getValue().backorderId()).isEqualTo(response.getBackorderId());
        assertThat(factCaptor.getValue().quantityShort()).isEqualTo(5);
    }

    @Test
    @DisplayName("createBackorder is idempotent: an existing OPEN backorder is returned, not duplicated")
    void createBackorder_existingOpen_returnsExisting() {
        BackorderRecord existing = openBackorder(WORKORDER_LINE_A, 5, Instant.parse("2026-07-22T00:00:00Z"));
        when(backorderRepository.findByWorkorderLineIdAndSkuAndStatus(WORKORDER_LINE_A, SKU, BackorderStatus.OPEN))
                .thenReturn(Optional.of(existing));

        BackorderResponse response = service.createBackorder(WORKORDER_LINE_A, SKU, 5, LOCATION_ID);

        assertThat(response.getBackorderId()).isEqualTo(existing.getBackorderId());
        verify(backorderRepository, never()).save(any());
        verifyNoInteractions(ledgerPostingService);
        verify(inventoryFactPublisher, never()).recordBackorderCreated(any());
    }

    @Test
    @DisplayName("Stock arrival auto-resolves the older backorder first when only one can be covered")
    void onInboundAvailability_resolvesOldestFirst_whenBudgetCoversOne() {
        BackorderRecord older = openBackorder(WORKORDER_LINE_A, 5, Instant.parse("2026-07-20T00:00:00Z"));
        BackorderRecord newer = openBackorder(WORKORDER_LINE_B, 5, Instant.parse("2026-07-21T00:00:00Z"));
        when(backorderRepository.findBySkuAndLocationIdAndStatusOrderByCreatedAtAsc(
                        SKU, LOCATION_ID, BackorderStatus.OPEN))
                .thenReturn(List.of(older, newer));
        when(reservationRepository.findByWorkorderLineId(any())).thenReturn(Optional.empty());
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
                .requiredQuantity(5)
                .priority(5)
                .status(ReservationStatus.BACKORDERED)
                .build();
        when(reservationRepository.findByWorkorderLineId(WORKORDER_LINE_A)).thenReturn(Optional.of(reservation));
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
        when(reservationRepository.findByWorkorderLineId(any())).thenReturn(Optional.empty());
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
                .quantityShort(quantityShort)
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
                .onHand(atp)
                .allocated(0)
                .atp(atp)
                .build();
    }
}
