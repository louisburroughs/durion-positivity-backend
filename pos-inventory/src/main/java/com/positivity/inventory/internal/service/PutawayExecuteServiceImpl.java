package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayExecuteService;
import com.positivity.inventory.service.PutawayValidationService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PutawayExecuteServiceImpl implements PutawayExecuteService {

    private final PutawayTaskRepository putawayTaskRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final PutawayValidationService putawayValidationService;

    @Override
    @Transactional
    public @NonNull PutawayExecutionResponse executePutaway(
            @NonNull String taskId,
            @NonNull PutawayExecutionRequest request,
            @NonNull String actorId) {
        UUID parsedTaskId = parseRequiredUuid(taskId, "taskId");

        PutawayTask task = putawayTaskRepository.findByIdForUpdate(parsedTaskId)
                .or(() -> putawayTaskRepository.findById(parsedTaskId))
                .orElseThrow(() -> new TaskNotFoundException(parsedTaskId));

        putawayValidationService.validatePutawayExecution(request);

        Instant now = Instant.now();
        InventoryLedgerEntry ledgerEntry = InventoryLedgerEntry.builder()
                .stockItemId(request.getSkuId())
                .eventType(InventoryLedgerEventType.PUTAWAY)
                .changeInQuantity(-request.getQuantity())
                .quantityAfter(0)
                .transactionUserId(actorId)
                .locationId(request.getSourceLocationId())
                .notes("Putaway from " + request.getSourceLocationId() + " to " + request.getDestinationLocationId())
                .timestamp(now)
                .build();

        InventoryLedgerEntry savedLedgerEntry = inventoryLedgerEntryRepository.save(ledgerEntry);

        InventoryLedgerEntry destinationLedgerEntry = InventoryLedgerEntry.builder()
                .stockItemId(request.getSkuId())
                .eventType(InventoryLedgerEventType.PUTAWAY)
                .changeInQuantity(request.getQuantity())
                .quantityAfter(0)
                .transactionUserId(actorId)
                .locationId(request.getDestinationLocationId())
                .notes("Putaway destination receipt from " + request.getSourceLocationId() + " to "
                        + request.getDestinationLocationId())
                .timestamp(now)
                .build();
        inventoryLedgerEntryRepository.save(destinationLedgerEntry);

        task.setStatus(PutawayTaskStatus.COMPLETED);
        task.setActualDestinationLocationId(request.getDestinationLocationId());
        putawayTaskRepository.save(task);

        return PutawayExecutionResponse.builder()
                .ledgerEntryId(savedLedgerEntry.getLedgerEntryId() != null
                        ? savedLedgerEntry.getLedgerEntryId().toString()
                        : null)
                .taskId(task.getTaskId() != null ? task.getTaskId().toString() : null)
                .skuId(request.getSkuId())
                .sourceLocationId(request.getSourceLocationId())
                .destinationLocationId(request.getDestinationLocationId())
                .quantityMoved(request.getQuantity())
                .transactionType("PUTAWAY")
                .status(PutawayTaskStatus.COMPLETED.name())
                .executedAt(now.toString())
                .actorId(actorId)
                .build();
    }

    private UUID parseRequiredUuid(String rawValue, String fieldName) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        try {
            return UUID.fromString(rawValue);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " must be a valid UUID", ex);
        }
    }
}