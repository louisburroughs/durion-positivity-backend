package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.entity.PickTaskEntity;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PickTaskRepository;
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

    @Mock
    private PickTaskRepository pickTaskRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Captor
    private ArgumentCaptor<List<InventoryLedgerEntry>> entriesCaptor;

    private ConsumptionServiceImpl inMemoryService() {
        return new ConsumptionServiceImpl();
    }

    private ConsumptionServiceImpl persistentService() {
        return new ConsumptionServiceImpl(pickTaskRepository, inventoryLedgerEntryRepository);
    }

    // ─── CS1: consumePickedItems — valid picks → ConsumptionResponse fields ─────

    @Test
    @DisplayName("in-memory mode returns response with one ledger id per item")
    void consumePickedItems_inMemoryMode_returnsPopulatedResponse() {
        UUID workorderId = UUID.randomUUID();
        UUID pickListId = UUID.randomUUID();
        List<ConsumeItemLine> items = List.of(
                new ConsumeItemLine(UUID.randomUUID(), UUID.randomUUID(), 2),
                new ConsumeItemLine(UUID.randomUUID(), UUID.randomUUID(), 4));
        ConsumeItemsRequest request = new ConsumeItemsRequest(workorderId, pickListId, items);

        ConsumptionResponse result = inMemoryService().consumePickedItems(request);

        assertThat(result.getConsumptionId()).isNotNull();
        assertThat(result.getWorkorderId()).isEqualTo(workorderId);
        assertThat(result.getPickListId()).isEqualTo(pickListId);
        assertThat(result.getTotalItemsConsumed()).isEqualTo(2);
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getLedgerEntryIds()).hasSize(2).doesNotContainNull();
    }

    // ─── CS2: consumePickedItems — ledger entries created per consumed item ──────

    @Test
    @DisplayName("null items are treated as empty list in in-memory mode")
    void consumePickedItems_inMemoryMode_withNullItems_returnsEmptyLedgerIds() {
        UUID workorderId = UUID.randomUUID();
        ConsumeItemsRequest request = new ConsumeItemsRequest(workorderId, UUID.randomUUID(), null);

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
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(UUID.randomUUID(), UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workorderId");
    }

    @Test
    @DisplayName("in-memory mode rejects quantity above 100")
    void consumePickedItems_inMemoryMode_withTooLargeQuantity_throwsIllegalArgumentException() {
        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(UUID.randomUUID(), UUID.randomUUID(), 101)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds picked quantity");
    }

    @Test
    @DisplayName("in-memory mode with one quantity-1 item throws not picked exception")
    void consumePickedItems_inMemoryMode_withSingleQuantityOneItem_throwsNotPicked() {
        UUID pickTaskId = UUID.randomUUID();
        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(pickTaskId, UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> inMemoryService().consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("not picked");
    }

    // ─── CS4: consumePickedItems — quantity exceeds picked → exception ────────

    @Test
    @DisplayName("persistent mode saves one ledger entry per line and returns non-null saved IDs")
    void consumePickedItems_persistentMode_successPath_buildsAndSavesLedgerEntries() {
        ConsumptionServiceImpl service = persistentService();
        UUID workorderId = UUID.randomUUID();
        UUID pickTaskId1 = UUID.randomUUID();
        UUID pickTaskId2 = UUID.randomUUID();
        UUID skuId2 = UUID.randomUUID();

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                workorderId,
                UUID.randomUUID(),
                List.of(
                        new ConsumeItemLine(pickTaskId1, null, 2),
                        new ConsumeItemLine(pickTaskId2, skuId2, 3)));

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

        UUID savedId = UUID.randomUUID();
        InventoryLedgerEntry savedOne = InventoryLedgerEntry.builder().ledgerEntryId(savedId).build();
        InventoryLedgerEntry savedTwo = InventoryLedgerEntry.builder().ledgerEntryId(null).build();
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
        UUID missingTaskId = UUID.randomUUID();

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(missingTaskId, UUID.randomUUID(), 1)));

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
        UUID pickTaskId = UUID.randomUUID();

        PickTaskEntity task = PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .status(PickTaskStatus.PENDING)
                .quantityPicked(10)
                .build();
        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(task));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(pickTaskId, UUID.randomUUID(), 1)));

        assertThatThrownBy(() -> service.consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("not picked");
    }

    @Test
    @DisplayName("persistent mode throws when requested quantity exceeds picked quantity")
    void consumePickedItems_persistentMode_withQuantityExceedingPicked_throwsWorkorderConsumptionException() {
        ConsumptionServiceImpl service = persistentService();
        UUID pickTaskId = UUID.randomUUID();

        PickTaskEntity task = PickTaskEntity.builder()
                .pickTaskId(pickTaskId)
                .status(PickTaskStatus.PICKED)
                .quantityPicked(2)
                .build();
        when(pickTaskRepository.findById(pickTaskId)).thenReturn(Optional.of(task));

        ConsumeItemsRequest request = new ConsumeItemsRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new ConsumeItemLine(pickTaskId, UUID.randomUUID(), 3)));

        assertThatThrownBy(() -> service.consumePickedItems(request))
                .isInstanceOf(WorkorderConsumptionException.class)
                .hasMessageContaining("exceeds picked quantity");
    }
}
