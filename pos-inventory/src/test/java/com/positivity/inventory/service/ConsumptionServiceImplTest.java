package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
import com.positivity.inventory.internal.service.ConsumptionServiceImpl;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsumptionServiceImpl")
class ConsumptionServiceImplTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private com.positivity.inventory.internal.repository.ReservationRepository reservationRepository;

    @Mock
    private com.positivity.inventory.internal.repository.AllocationRepository allocationRepository;

    @Captor
    private ArgumentCaptor<List<InventoryLedgerEntry>> entriesCaptor;

    private ConsumptionServiceImpl inMemoryService() {
        return persistentService();
    }

    private ConsumptionServiceImpl persistentService() {
        return new ConsumptionServiceImpl(
                pickTaskRepository,
                inventoryLedgerEntryRepository,
                reservationRepository,
                allocationRepository,
                FIXED_CLOCK);
    }

    // ─── CS1: consumePickedItems — valid picks → ConsumptionResponse fields ─────

    @Test
    @DisplayName("in-memory mode returns response with one ledger id per item")
    void consumePickedItems_inMemoryMode_returnsPopulatedResponse() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pickListId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pickTaskId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pickTaskId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        List<ConsumeItemLine> items = List.of(
                new ConsumeItemLine(pickTaskId1, UUID.fromString("00000000-0000-0000-0000-000000000001"), 2),
                new ConsumeItemLine(pickTaskId2, UUID.fromString("00000000-0000-0000-0000-000000000001"), 4));
        ConsumeItemsRequest request = new ConsumeItemsRequest(workorderId, pickListId, items);

        when(pickTaskRepository.findById(pickTaskId1))
                .thenReturn(Optional.of(PickTaskEntity.builder()
                        .pickTaskId(pickTaskId1)
                        .status(PickTaskStatus.PICKED)
                        .quantityPicked(2)
                        .build()));
        when(pickTaskRepository.findById(pickTaskId2))
                .thenReturn(Optional.of(PickTaskEntity.builder()
                        .pickTaskId(pickTaskId2)
                        .status(PickTaskStatus.PICKED)
                        .quantityPicked(4)
                        .build()));
        when(inventoryLedgerEntryRepository.saveAll(any()))
                .thenReturn(List.of(
                        InventoryLedgerEntry.builder()
                                .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build(),
                        InventoryLedgerEntry.builder()
                                .ledgerEntryId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .build()));

        ConsumptionResponse result = inMemoryService().consumePickedItems(request);

        assertThat(result.getConsumptionId()).isNotNull();
        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getPickListId()).isEqualTo(pickListId);
        assertThat(result.getTotalItemsConsumed()).isEqualTo(6); // sum of quantities (2 + 4)
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getLedgerEntryIds()).hasSize(2).doesNotContainNull();
    }

    // ─── CS2: consumePickedItems — ledger entries created per consumed item ──────

    @Test
    @DisplayName("null items are treated as empty list")
    void consumePickedItems_inMemoryMode_withNullItems_returnsEmptyLedgerIds() {
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ConsumeItemsRequest request =
                new ConsumeItemsRequest(workorderId, UUID.fromString("00000000-0000-0000-0000-000000000001"), null);

        when(inventoryLedgerEntryRepository.saveAll(any())).thenReturn(List.of());

        ConsumptionResponse result = inMemoryService().consumePickedItems(request);

        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getTotalItemsConsumed()).isZero();
        assertThat(result.getLedgerEntryIds()).isEmpty();
    }

    // ─── CS3: consumePickedItems — pickTask NOT in PICKED status → exception ─────

    @Test
    @DisplayName("null workorderId throws IllegalArgumentException")
    void consumePickedItems_withNullWorkorderId_throwsValidationException() {
        ConsumeItemsRequest request = new ConsumeItemsRequest(
                null,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        1)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workorderId");
    }

    @Test
    @DisplayName("rejects quantity that exceeds picked quantity")
    void consumePickedItems_inMemoryMode_withTooLargeQuantity_throwsIllegalArgumentException() {
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(pickTaskRepository.findById(pickTaskId))
                .thenReturn(Optional.of(PickTaskEntity.builder()
                        .pickTaskId(pickTaskId)
                        .status(PickTaskStatus.PICKED)
                        .quantityPicked(0)
                        .build()));
        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 101)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("exceeds picked quantity");
    }

    @Test
    @DisplayName("in-memory mode with one quantity-1 item throws not picked exception")
    void consumePickedItems_inMemoryMode_withSingleQuantityOneItem_throwsNotPicked() {
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(pickTaskRepository.findById(pickTaskId))
                .thenReturn(Optional.of(PickTaskEntity.builder()
                        .pickTaskId(pickTaskId)
                        .status(PickTaskStatus.PENDING)
                        .quantityPicked(10)
                        .build()));
        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("not picked");
    }

    // ─── CS4: consumePickedItems — quantity exceeds picked → exception ────────

    @Test
    @DisplayName("persistent mode saves one ledger entry per line and returns non-null saved IDs")
    void consumePickedItems_persistentMode_successPath_buildsAndSavesLedgerEntries() {
        ConsumptionServiceImpl service = persistentService();
        UUID workorderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pickTaskId1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID pickTaskId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID skuId2 = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                workorderId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId1, null, 2), new ConsumeItemLine(pickTaskId2, skuId2, 3)));

        PickTaskEntity pickedTask1 = PickTaskEntity.builder()
                .pickTaskId(pickTaskId1)
                .status(PickTaskStatus.PICKED)
                .quantityPicked(5)
                .build();
        PickTaskEntity pickedTask2 = PickTaskEntity.builder()
                .pickTaskId(pickTaskId2)
                .status(PickTaskStatus.PICKED)
                .quantityPicked(3)
                .build();

        when(pickTaskRepository.findById(pickTaskId1)).thenReturn(Optional.of(pickedTask1));
        when(pickTaskRepository.findById(pickTaskId2)).thenReturn(Optional.of(pickedTask2));

        UUID savedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        InventoryLedgerEntry savedOne =
                InventoryLedgerEntry.builder().ledgerEntryId(savedId).build();
        InventoryLedgerEntry savedTwo =
                InventoryLedgerEntry.builder().ledgerEntryId(null).build();
        when(inventoryLedgerEntryRepository.saveAll(any())).thenReturn(List.of(savedOne, savedTwo));

        ConsumptionResponse response = service.consumePickedItems(request);

        verify(inventoryLedgerEntryRepository).saveAll(entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(2);
        InventoryLedgerEntry firstEntry = entriesCaptor.getValue().getFirst();
        InventoryLedgerEntry secondEntry = entriesCaptor.getValue().get(1);
        assertThat(firstEntry.getStockItemId()).isEqualTo("");
        assertThat(firstEntry.getEventType()).isEqualTo(InventoryLedgerEventType.WORKORDER_CONSUMPTION);
        assertThat(firstEntry.getChangeInQuantity()).isEqualTo(-2);
        assertThat(firstEntry.getQuantityAfter()).isZero();
        assertThat(firstEntry.getNotes()).contains(workorderId.toString());
        assertThat(secondEntry.getStockItemId()).isEqualTo(skuId2.toString());
        assertThat(secondEntry.getChangeInQuantity()).isEqualTo(-3);

        assertThat(response.getWorkorderId()).isEqualTo(workorderId);
        assertThat(response.getTotalItemsConsumed()).isEqualTo(5);
        assertThat(response.getLedgerEntryIds()).containsExactly(savedId);
    }

    @Test
    @DisplayName("persistent mode throws ResourceNotFoundException when pick task is missing")
    void consumePickedItems_persistentMode_withMissingTask_throwsResourceNotFound() {
        ConsumptionServiceImpl service = persistentService();
        UUID missingTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(
                        missingTaskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

        when(pickTaskRepository.findById(missingTaskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consumePickedItems(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PickTask");
    }

    // ─── CS5: consumePickedItems — null workorderId → validation exception ────

    @Test
    @DisplayName("persistent mode throws not picked when task status is not PICKED")
    void consumePickedItems_persistentMode_withTaskNotPicked_throwsWorkorderConsumptionException() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        PickTaskEntity task = PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .status(PickTaskStatus.PENDING)
                .quantityPicked(10)
                .build();
        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(task));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)));

        assertThatThrownBy(() -> service.consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("not picked");
    }

    @Test
    @DisplayName("persistent mode throws when requested quantity exceeds picked quantity")
    void consumePickedItems_persistentMode_withQuantityExceedingPicked_throwsWorkorderConsumptionException() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        PickTaskEntity task = PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .status(PickTaskStatus.PICKED)
                .quantityPicked(2)
                .build();
        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(task));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, UUID.fromString("00000000-0000-0000-0000-000000000001"), 3)));

        assertThatThrownBy(() -> service.consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("exceeds picked quantity");
    }

    // ─── CAP-218 #662: allocation closure on consumption ─────────────────────────

    private static final UUID WO_LINE_ID = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID STOCK_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000dd");

    private com.positivity.inventory.internal.entity.ReservationEntity reservationWithLine() {
        return com.positivity.inventory.internal.entity.ReservationEntity.builder()
                .reservationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .workorderLineId(WO_LINE_ID)
                .stockItemId(STOCK_ITEM_ID)
                .requiredQuantity(5)
                .allocatedQuantity(5)
                .status(com.positivity.inventory.internal.enums.ReservationStatus.FULFILLED)
                .build();
    }

    private com.positivity.inventory.internal.entity.AllocationEntity locatedHardAllocation(
            com.positivity.inventory.internal.entity.ReservationEntity reservation, int quantity) {
        return com.positivity.inventory.internal.entity.AllocationEntity.builder()
                .allocationId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .reservation(reservation)
                .locationId(LOCATION_ID)
                .allocatedQuantity(quantity)
                .allocationState(com.positivity.inventory.internal.enums.AllocationState.HARD)
                .status(com.positivity.inventory.internal.enums.AllocationStatus.ALLOCATED)
                .build();
    }

    private PickTaskEntity pickedTaskWithLine(UUID pickTaskId) {
        return PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .workorderLineId(WO_LINE_ID)
                .status(PickTaskStatus.PICKED)
                .quantityPicked(5)
                .build();
    }

    @Test
    @DisplayName("#662: full consumption writes ALLOCATION_RELEASED and closes the allocation")
    void consumePickedItems_fullConsumption_releasesAllocationAndClosesIt() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var reservation = reservationWithLine();
        var allocation = locatedHardAllocation(reservation, 5);

        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(pickedTaskWithLine(pickTaskId)));
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(STOCK_ITEM_ID))
                .thenReturn(10);
        when(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED))
                .thenReturn(0);
        when(inventoryLedgerEntryRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(allocationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, STOCK_ITEM_ID, 5)));

        service.consumePickedItems(request);

        verify(inventoryLedgerEntryRepository).saveAll(entriesCaptor.capture());
        List<InventoryLedgerEntry> entries = entriesCaptor.getValue();
        assertThat(entries).hasSize(2);
        InventoryLedgerEntry released = entries.get(1);
        assertThat(released.getEventType()).isEqualTo(InventoryLedgerEventType.ALLOCATION_RELEASED);
        assertThat(released.getChangeInQuantity()).isEqualTo(5);
        assertThat(released.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(released.getStockItemId()).isEqualTo(STOCK_ITEM_ID.toString());
        assertThat(released.getSourceTransactionId())
                .isEqualTo(allocation.getAllocationId().toString());
        assertThat(allocation.getStatus()).isEqualTo(com.positivity.inventory.internal.enums.AllocationStatus.RELEASED);
    }

    @Test
    @DisplayName("#662: partial consumption releases exactly the consumed quantity and keeps allocation open")
    void consumePickedItems_partialConsumption_releasesConsumedQuantityOnly() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var reservation = reservationWithLine();
        var allocation = locatedHardAllocation(reservation, 5);

        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(pickedTaskWithLine(pickTaskId)));
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(STOCK_ITEM_ID))
                .thenReturn(10);
        when(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED))
                .thenReturn(0);
        when(inventoryLedgerEntryRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, STOCK_ITEM_ID, 2)));

        service.consumePickedItems(request);

        verify(inventoryLedgerEntryRepository).saveAll(entriesCaptor.capture());
        InventoryLedgerEntry released = entriesCaptor.getValue().get(1);
        assertThat(released.getChangeInQuantity()).isEqualTo(2);
        assertThat(allocation.getStatus())
                .isEqualTo(com.positivity.inventory.internal.enums.AllocationStatus.ALLOCATED);
        verify(allocationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("#662: consumption of unallocated stock writes no allocation events")
    void consumePickedItems_noReservation_writesNoAllocationEvents() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(pickedTaskWithLine(pickTaskId)));
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.empty());
        when(inventoryLedgerEntryRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, STOCK_ITEM_ID, 2)));

        service.consumePickedItems(request);

        verify(inventoryLedgerEntryRepository).saveAll(entriesCaptor.capture());
        assertThat(entriesCaptor.getValue()).hasSize(1);
        assertThat(entriesCaptor.getValue().getFirst().getEventType())
                .isEqualTo(InventoryLedgerEventType.WORKORDER_CONSUMPTION);
    }

    @Test
    @DisplayName("#662: already partially released allocation only releases its remainder")
    void consumePickedItems_partiallyReleasedAllocation_releasesRemainderOnly() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var reservation = reservationWithLine();
        var allocation = locatedHardAllocation(reservation, 5);

        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(pickedTaskWithLine(pickTaskId)));
        when(reservationRepository.findByWorkorderLineId(WO_LINE_ID)).thenReturn(Optional.of(reservation));
        when(allocationRepository.findByReservation(reservation)).thenReturn(List.of(allocation));
        when(inventoryLedgerEntryRepository.calculateOnHandQuantity(STOCK_ITEM_ID))
                .thenReturn(10);
        when(inventoryLedgerEntryRepository.sumChangeBySourceTransactionIdAndEventType(
                        allocation.getAllocationId().toString(), InventoryLedgerEventType.ALLOCATION_RELEASED))
                .thenReturn(3);
        when(inventoryLedgerEntryRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(allocationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of(new ConsumeItemLine(pickTaskId, STOCK_ITEM_ID, 4)));

        service.consumePickedItems(request);

        verify(inventoryLedgerEntryRepository).saveAll(entriesCaptor.capture());
        InventoryLedgerEntry released = entriesCaptor.getValue().get(1);
        // invariant: total RELEASED never exceeds allocatedQuantity (3 + 2 = 5)
        assertThat(released.getChangeInQuantity()).isEqualTo(2);
        assertThat(allocation.getStatus()).isEqualTo(com.positivity.inventory.internal.enums.AllocationStatus.RELEASED);
    }
}
