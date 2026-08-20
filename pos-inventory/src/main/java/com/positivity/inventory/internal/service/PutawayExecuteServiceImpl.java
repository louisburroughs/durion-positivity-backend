package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayExecuteService;
import com.positivity.inventory.service.PutawayValidationService;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PutawayExecuteServiceImpl implements PutawayExecuteService {

    private final PutawayTaskRepository putawayTaskRepository;
    private final InventoryLedgerEntryRepository inventoryLedgerEntryRepository;
    private final LedgerPostingService ledgerPostingService;
    private final InventoryFactPublisher inventoryFactPublisher;
    private final PutawayValidationService putawayValidationService;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull PutawayExecutionResponse executePutaway(
            @NonNull String taskId, @NonNull PutawayExecutionRequest request) {
        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("System");
        UUID parsedTaskId = parseRequiredUuid(taskId, "taskId");

        PutawayTask task = putawayTaskRepository
                .findByIdForUpdate(parsedTaskId)
                .or(() -> putawayTaskRepository.findById(parsedTaskId))
                .orElseThrow(() -> new TaskNotFoundException(parsedTaskId));

        ValidationResult validationResult = putawayValidationService.validatePutawayExecution(request);
        ensureValidationPassed(validationResult);

        Instant now = Instant.now(clock);
        // The putaway request quantity is still an int (a bin move of discrete units; the UOM
        // work in ADR-0055 stage 3 revisits it), so this widens at the boundary — the ledger
        // running totals it is added to are decimal.
        BigDecimal movedQuantity = BigDecimal.valueOf(request.getQuantity());
        BigDecimal sourceOnHandBefore = onHandQuantityAtLocation(request.getSkuId(), request.getSourceLocationId());
        BigDecimal sourceOnHandAfter = sourceOnHandBefore.subtract(movedQuantity);

        BigDecimal destinationOnHandBefore =
                onHandQuantityAtLocation(request.getSkuId(), request.getDestinationLocationId());
        BigDecimal destinationBaseOnHand = request.getSourceLocationId().equals(request.getDestinationLocationId())
                ? sourceOnHandAfter
                : destinationOnHandBefore;
        BigDecimal destinationOnHandAfter = destinationBaseOnHand.add(movedQuantity);

        InventoryLedgerEntry sourceLedgerEntry = InventoryLedgerEntry.builder()
                .stockItemId(request.getSkuId())
                .eventType(InventoryLedgerEventType.PUTAWAY)
                .changeInQuantity(movedQuantity.negate())
                .quantityAfter(sourceOnHandAfter)
                .transactionUserId(actorId)
                .locationId(request.getSourceLocationId())
                .notes("Putaway from " + request.getSourceLocationId() + " to " + request.getDestinationLocationId())
                .timestamp(now)
                .build();

        InventoryLedgerEntry savedSourceLedgerEntry = ledgerPostingService.post(sourceLedgerEntry);
        inventoryFactPublisher.markEntry(savedSourceLedgerEntry);

        InventoryLedgerEntry destinationLedgerEntry = InventoryLedgerEntry.builder()
                .stockItemId(request.getSkuId())
                .eventType(InventoryLedgerEventType.PUTAWAY)
                .changeInQuantity(movedQuantity)
                .quantityAfter(destinationOnHandAfter)
                .transactionUserId(actorId)
                .locationId(request.getDestinationLocationId())
                .notes("Putaway destination receipt from " + request.getSourceLocationId() + " to "
                        + request.getDestinationLocationId())
                .timestamp(now)
                .build();
        ledgerPostingService.post(destinationLedgerEntry);
        inventoryFactPublisher.markEntry(destinationLedgerEntry);

        task.setStatus(PutawayTaskStatus.COMPLETED);
        task.setActualDestinationLocationId(request.getDestinationLocationId());
        putawayTaskRepository.save(task);

        return PutawayExecutionResponse.builder()
                .ledgerEntryId(
                        savedSourceLedgerEntry.getLedgerEntryId() != null
                                ? savedSourceLedgerEntry.getLedgerEntryId().toString()
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

    private void ensureValidationPassed(ValidationResult validationResult) {
        if (validationResult == null) {
            throw new IllegalStateException("Putaway validation returned no result");
        }
        if (validationResult.isValid()) {
            return;
        }

        ValidationResult.ValidationError firstError =
                validationResult.getErrors().isEmpty()
                        ? null
                        : validationResult.getErrors().get(0);
        String errorCode = firstError != null ? firstError.getErrorCode() : "PUTAWAY_VALIDATION_FAILED";
        String message = firstError != null ? firstError.getMessage() : "Putaway validation failed";
        throw new PutawayValidationException(errorCode, message);
    }

    private BigDecimal onHandQuantityAtLocation(String skuId, UUID locationId) {
        return Quantities.nz(inventoryLedgerEntryRepository.calculateOnHandQuantityAtLocation(skuId, locationId));
    }
}
