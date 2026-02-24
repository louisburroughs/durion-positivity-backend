package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.PutawayExecuteServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PutawayExecuteServiceImplTest {

    @Mock
    private PutawayTaskRepository putawayTaskRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private PutawayValidationService putawayValidationService;

    @InjectMocks
    private PutawayExecuteServiceImpl putawayExecuteService;

    private PutawayExecutionRequest request;
    private PutawayTask task;
    private UUID taskId;
    private String actorId;

    @BeforeEach
    void setUp() {
        taskId = UUID.randomUUID();
        actorId = "user-123";

        request = new PutawayExecutionRequest(
                "sku-abc",
                "source-loc",
                "dest-loc",
                10);

        task = PutawayTask.builder()
                .taskId(taskId)
                .status(PutawayTaskStatus.UNASSIGNED)
                .build();
    }

    @Test
    void executePutaway_happyPath() {
        // Given
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(inventoryLedgerEntryRepository.save(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                });

        // When
        PutawayExecutionResponse response = putawayExecuteService.executePutaway(taskId.toString(), request, actorId);

        // Then
        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository).save(ledgerCaptor.capture());
        InventoryLedgerEntry savedLedgerEntry = ledgerCaptor.getValue();

        assertThat(savedLedgerEntry.getChangeInQuantity()).isEqualTo(-10);
        assertThat(savedLedgerEntry.getStockItemId()).isEqualTo("sku-abc");
        assertThat(savedLedgerEntry.getLocationId()).isEqualTo("source-loc");
        assertThat(savedLedgerEntry.getTransactionUserId()).isEqualTo(actorId);

        ArgumentCaptor<PutawayTask> taskCaptor = ArgumentCaptor.forClass(PutawayTask.class);
        verify(putawayTaskRepository).save(taskCaptor.capture());
        PutawayTask savedTask = taskCaptor.getValue();

        assertThat(savedTask.getStatus()).isEqualTo(PutawayTaskStatus.COMPLETED);
        assertThat(savedTask.getActualDestinationLocationId()).isEqualTo("dest-loc");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.COMPLETED.name());
        assertThat(response.getQuantityMoved()).isEqualTo(10);
        assertThat(response.getSkuId()).isEqualTo("sku-abc");
        assertThat(response.getActorId()).isEqualTo(actorId);
    }

    @Test
    void executePutaway_throwsTaskNotFoundException() {
        // Given
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        // When & Then
        assertThrows(TaskNotFoundException.class, () -> {
            putawayExecuteService.executePutaway(taskId.toString(), request, actorId);
        });
    }

    @Test
    void executePutaway_throwsLocationNotValidForSkuException() {
        // Given
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        doThrow(new LocationNotValidForSkuException("dest-loc", "sku-abc", "reason"))
                .when(putawayValidationService).validatePutawayExecution(request);

        // When & Then
        assertThrows(LocationNotValidForSkuException.class, () -> {
            putawayExecuteService.executePutaway(taskId.toString(), request, actorId);
        });
    }

    @Test
    void executePutaway_throwsLocationAtCapacityException() {
        // Given
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        doThrow(new LocationAtCapacityException("dest-loc", 100, 100))
                .when(putawayValidationService).validatePutawayExecution(request);

        // When & Then
        assertThrows(LocationAtCapacityException.class, () -> {
            putawayExecuteService.executePutaway(taskId.toString(), request, actorId);
        });
    }

    @Test
    void executePutaway_throwsNoOnHandAtSourceLocationException() {
        // Given
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        doThrow(new NoOnHandAtSourceLocationException("source-loc", "sku-abc"))
                .when(putawayValidationService).validatePutawayExecution(request);

        // When & Then
        assertThrows(NoOnHandAtSourceLocationException.class, () -> {
            putawayExecuteService.executePutaway(taskId.toString(), request, actorId);
        });
    }

    @Test
    void executePutaway_throwsIllegalArgumentExceptionForInvalidUUID() {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> {
            putawayExecuteService.executePutaway("invalid-uuid", request, actorId);
        });
    }
}
