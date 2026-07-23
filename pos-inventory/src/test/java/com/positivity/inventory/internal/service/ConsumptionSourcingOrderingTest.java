package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.entity.AllocationEntity;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.AllocationState;
import com.positivity.inventory.internal.enums.AllocationStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.enums.SourcingStrategy;
import com.positivity.inventory.internal.repository.AllocationRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingCandidate;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingDecision;
import com.positivity.inventory.internal.service.SourcingStrategyService.SourcingSelection;
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
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Sourcing-engine integration of the consumption allocation-close ordering
 * (odoo-parity H1, issue #1037): the engine's default FIFO decision preserves
 * the pre-H1 oldest-first order (regression), while a PROXIMITY decision
 * reorders the closes by location rank (divergence). The pre-H1 fixtures in
 * {@code ConsumptionServiceImplTest} remain unmodified and green via the
 * engine-less constructor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConsumptionServiceImpl sourcing-engine ordering")
class ConsumptionSourcingOrderingTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WO_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID STOCK_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID SCANNED_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID LOC_OLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID LOC_NEW = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID ALLOC_OLD = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ALLOC_NEW = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private AllocationRepository allocationRepository;

    @Mock
    private LedgerPostingService ledgerPostingService;

    @Mock
    private SourcingStrategyService sourcingStrategyService;

    @Captor
    private ArgumentCaptor<List<InventoryLedgerEntry>> entriesCaptor;

    @Captor
    private ArgumentCaptor<SourcingSelection> selectionCaptor;

    private ConsumptionServiceImpl service;
    private ReservationEntity reservation;

    @BeforeEach
    void setUp() {
        service = new ConsumptionServiceImpl(
                pickTaskRepository,
                inventoryLedgerEntryRepository,
                reservationRepository,
                allocationRepository,
                ledgerPostingService,
                mock(InventoryFactPublisher.class),
                FIXED_CLOCK,
                sourcingStrategyService);

        reservation = ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000009"))
                .workorderLineId(WO_LINE_ID)
                .stockItemId(STOCK_ITEM_ID)
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(ReservationStatus.FULFILLED)
                .build();

        when(pickTaskRepository.findById(PICK_TASK_ID))
                .thenReturn(Optional.of(PickTaskEntity.builder()
                        .pickTaskId(PICK_TASK_ID)
                        .workorderLineId(WO_LINE_ID)
                        .suggestedLocationId(SCANNED_LOCATION)
                        .status(PickTaskStatus.PICKED)
                        .quantityPicked(5)
                        .build()));
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.of(reservation));
        // Two partially-releasable HARD allocations at different locations; the
        // one at LOC_OLD is older than the one at LOC_NEW.
        when(allocationRepository.findByReservation(reservation))
                .thenReturn(List.of(
                        allocation(ALLOC_NEW, LOC_NEW, Instant.parse("2026-06-02T00:00:00Z")),
                        allocation(ALLOC_OLD, LOC_OLD, Instant.parse("2026-06-01T00:00:00Z"))));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(STOCK_ITEM_ID))
                .thenReturn(10);
        when(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        any(), any(InventoryLedgerEventType.class)))
                .thenReturn(0);
        when(ledgerPostingService.postAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AllocationEntity allocation(UUID allocationId, UUID locationId, Instant createdAt) {
        return AllocationEntity.builder()
                .allocationId(allocationId)
                .reservation(reservation)
                .locationId(locationId)
                .allocatedQuantity(3)
                .allocationState(AllocationState.HARD)
                .status(AllocationStatus.ALLOCATED)
                .createdAt(createdAt)
                .build();
    }

    private ConsumeItemsRequest requestFor(int quantity) {
        return new ConsumeItemsRequest(
                WORKORDER_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                List.of(new ConsumeItemLine(PICK_TASK_ID, STOCK_ITEM_ID, quantity)));
    }

    /** Ledger release entries, in posting order, keyed by sourceTransactionId (= allocation id). */
    private List<String> releasedAllocationIds() {
        verify(ledgerPostingService).postAll(entriesCaptor.capture());
        return entriesCaptor.getValue().stream()
                .filter(entry -> entry.getEventType() == InventoryLedgerEventType.ALLOCATION_RELEASED)
                .map(InventoryLedgerEntry::getSourceTransactionId)
                .toList();
    }

    @Test
    @DisplayName("regression: engine FIFO decision closes allocations oldest-first (pre-H1 behavior)")
    void fifoDecision_preservesOldestFirstOrder() {
        when(sourcingStrategyService.orderCandidates(any()))
                .thenReturn(new SourcingDecision(
                        SourcingStrategy.FIFO,
                        SourcingStrategy.FIFO,
                        List.of(new SourcingCandidate(LOC_NEW, null), new SourcingCandidate(LOC_OLD, null))));

        service.consumePickedItems(requestFor(4));

        // FIFO ignores the engine's location list and closes oldest-first.
        assertThat(releasedAllocationIds()).containsExactly(ALLOC_OLD.toString(), ALLOC_NEW.toString());
    }

    @Test
    @DisplayName("divergence: PROXIMITY decision closes allocations by engine location rank, not age")
    void proximityDecision_reordersByLocationRank() {
        when(sourcingStrategyService.orderCandidates(any()))
                .thenReturn(new SourcingDecision(
                        SourcingStrategy.PROXIMITY,
                        SourcingStrategy.PROXIMITY,
                        List.of(new SourcingCandidate(LOC_NEW, null), new SourcingCandidate(LOC_OLD, null))));

        service.consumePickedItems(requestFor(4));

        // The younger allocation at the nearer location closes first.
        assertThat(releasedAllocationIds()).containsExactly(ALLOC_NEW.toString(), ALLOC_OLD.toString());
    }

    @Test
    @DisplayName("the engine is asked with the task's scanned location as PROXIMITY reference")
    void engineReceivesScannedLocationAsReference() {
        when(sourcingStrategyService.orderCandidates(any()))
                .thenReturn(new SourcingDecision(
                        SourcingStrategy.FIFO, SourcingStrategy.FIFO, List.of(new SourcingCandidate(LOC_OLD, null))));

        service.consumePickedItems(requestFor(1));

        verify(sourcingStrategyService).orderCandidates(selectionCaptor.capture());
        SourcingSelection selection = selectionCaptor.getValue();
        assertThat(selection.stockItemId()).isEqualTo(STOCK_ITEM_ID.toString());
        assertThat(selection.referenceLocationId()).isEqualTo(SCANNED_LOCATION);
        assertThat(selection.candidates())
                .extracting(SourcingCandidate::locationId)
                .containsExactlyInAnyOrder(LOC_OLD, LOC_NEW);
    }
}
