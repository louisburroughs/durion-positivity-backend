package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.PutawayExecuteServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PutawayExecuteServiceImplTest {

    @Mock
    private PutawayTaskRepository putawayTaskRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private PutawayValidationService putawayValidationService;

    private PutawayExecuteServiceImpl putawayExecuteService;

    private PutawayExecutionRequest request;
    private PutawayTask task;
    private UUID taskId;
    private UUID sourceLocationId;
    private UUID destinationLocationId;

    private static final String DEFAULT_ACTOR = "System";

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-02-25T12:00:00Z"), ZoneOffset.UTC);
        putawayExecuteService = new PutawayExecuteServiceImpl(
                putawayTaskRepository,
                inventoryLedgerEntryRepository,
                putawayValidationService,
                fixedClock);

        taskId = UUID.randomUUID();
        sourceLocationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        destinationLocationId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        request = new PutawayExecutionRequest(
                "sku-abc",
                sourceLocationId,
                destinationLocationId,
                10);

        task = PutawayTask.builder()
                .taskId(taskId)
                .status(PutawayTaskStatus.UNASSIGNED)
                .build();
    }

    @Test
    void executePutaway_happyPath() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(putawayValidationService.validatePutawayExecution(request)).thenReturn(ValidationResult.success());
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("sku-abc", sourceLocationId))
                .thenReturn(50);
        when(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation("sku-abc", destinationLocationId))
                .thenReturn(5);
        when(inventoryLedgerEntryRepository.save(any(InventoryLedgerEntry.class)))
                .thenAnswer(invocation -> {
                    InventoryLedgerEntry entry = invocation.getArgument(0);
                    entry.setLedgerEntryId(UUID.randomUUID());
                    return entry;
                });

        PutawayExecutionResponse response = putawayExecuteService.executePutaway(taskId.toString(), request);

        ArgumentCaptor<InventoryLedgerEntry> ledgerCaptor = ArgumentCaptor.forClass(InventoryLedgerEntry.class);
        verify(inventoryLedgerEntryRepository, times(2)).save(ledgerCaptor.capture());
        List<InventoryLedgerEntry> allCaptured = ledgerCaptor.getAllValues();

        InventoryLedgerEntry sourceEntry = allCaptured.stream()
                .filter(e -> e.getChangeInQuantity() == -10)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No source decrement entry found"));
        assertThat(sourceEntry.getStockItemId()).isEqualTo("sku-abc");
        assertThat(sourceEntry.getLocationId()).isEqualTo(sourceLocationId);
        assertThat(sourceEntry.getTransactionUserId()).isEqualTo(DEFAULT_ACTOR);
        assertThat(sourceEntry.getQuantityAfter()).isEqualTo(40);

        InventoryLedgerEntry destinationEntry = allCaptured.stream()
                .filter(e -> e.getChangeInQuantity() == 10)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No destination credit entry found"));
        assertThat(destinationEntry.getStockItemId()).isEqualTo("sku-abc");
        assertThat(destinationEntry.getLocationId()).isEqualTo(destinationLocationId);
        assertThat(destinationEntry.getTransactionUserId()).isEqualTo(DEFAULT_ACTOR);
        assertThat(destinationEntry.getQuantityAfter()).isEqualTo(15);

        ArgumentCaptor<PutawayTask> taskCaptor = ArgumentCaptor.forClass(PutawayTask.class);
        verify(putawayTaskRepository).save(taskCaptor.capture());
        PutawayTask savedTask = taskCaptor.getValue();

        assertThat(savedTask.getStatus()).isEqualTo(PutawayTaskStatus.COMPLETED);
        assertThat(savedTask.getActualDestinationLocationId()).isEqualTo(destinationLocationId);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(PutawayTaskStatus.COMPLETED.name());
        assertThat(response.getQuantityMoved()).isEqualTo(10);
        assertThat(response.getSkuId()).isEqualTo("sku-abc");
        assertThat(response.getActorId()).isEqualTo(DEFAULT_ACTOR);
    }

    @Test
    void executePutaway_throwsTaskNotFoundException() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.empty());
        when(putawayTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(TaskNotFoundException.class, () -> putawayExecuteService.executePutaway(taskId.toString(), request));
    }

    @Test
    void executePutaway_throwsLocationNotValidForSkuException() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new LocationNotValidForSkuException(destinationLocationId, "sku-abc", "reason"))
                .when(putawayValidationService).validatePutawayExecution(request);

        assertThrows(LocationNotValidForSkuException.class,
                () -> putawayExecuteService.executePutaway(taskId.toString(), request));
    }

    @Test
    void executePutaway_throwsLocationAtCapacityException() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new LocationAtCapacityException(destinationLocationId, 100, 100))
                .when(putawayValidationService).validatePutawayExecution(request);

        assertThrows(LocationAtCapacityException.class,
                () -> putawayExecuteService.executePutaway(taskId.toString(), request));
    }

    @Test
    void executePutaway_throwsNoOnHandAtSourceLocationException() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        doThrow(new NoOnHandAtSourceLocationException(sourceLocationId, "sku-abc"))
                .when(putawayValidationService).validatePutawayExecution(request);

        assertThrows(NoOnHandAtSourceLocationException.class,
                () -> putawayExecuteService.executePutaway(taskId.toString(), request));
    }

    @Test
    void executePutaway_throwsIllegalArgumentExceptionForInvalidUUID() {
        assertThrows(IllegalArgumentException.class,
                () -> putawayExecuteService.executePutaway("invalid-uuid", request));
    }

    @Test
    void executePutaway_throwsPutawayValidationExceptionWhenValidationResultContainsErrors() {
        when(putawayTaskRepository.findByIdForUpdate(taskId)).thenReturn(Optional.of(task));
        when(putawayValidationService.validatePutawayExecution(request))
                .thenReturn(ValidationResult.failure("INSUFFICIENT_QUANTITY", "not enough stock"));

        assertThrows(PutawayValidationException.class,
                () -> putawayExecuteService.executePutaway(taskId.toString(), request));
        verify(inventoryLedgerEntryRepository, never()).save(any(InventoryLedgerEntry.class));
        verify(putawayTaskRepository, never()).save(any(PutawayTask.class));
    }
}
