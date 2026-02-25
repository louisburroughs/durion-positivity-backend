package com.positivity.inventory.internal.service;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.internal.dto.PutawayExecutionRequest;
import com.positivity.inventory.internal.dto.ValidationResult;
import com.positivity.inventory.internal.dto.putaway.PutawayExecutionResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayExecuteService;
import com.positivity.inventory.service.PutawayValidationService;
import com.positivity.security.common.SecurityContextHelper;

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
                        @NonNull PutawayExecutionRequest request) {
                String actorId = SecurityContextHelper.getCurrentUsernameOrDefault("System");
                UUID parsedTaskId = parseRequiredUuid(taskId, "taskId");

                PutawayTask task = putawayTaskRepository.findByIdForUpdate(parsedTaskId)
                                .or(() -> putawayTaskRepository.findById(parsedTaskId))
                                .orElseThrow(() -> new TaskNotFoundException(parsedTaskId));

                ValidationResult validationResult = putawayValidationService.validatePutawayExecution(request);
                ensureValidationPassed(validationResult);

                Instant now = Instant.now();
                InventoryLedgerEntry ledgerEntry = InventoryLedgerEntry.builder()
                                .stockItemId(request.getSkuId())
                                .eventType(InventoryLedgerEventType.PUTAWAY)
                                .changeInQuantity(-request.getQuantity())
                                // TODO(CAP-215): quantityAfter requires InventoryOnHand read model; 0 is a
                                // placeholder
                                .quantityAfter(0)
                                .transactionUserId(actorId)
                                .locationId(request.getSourceLocationId())
                                .notes("Putaway from " + request.getSourceLocationId() + " to "
                                                + request.getDestinationLocationId())
                                .timestamp(now)
                                .build();

                InventoryLedgerEntry savedLedgerEntry = inventoryLedgerEntryRepository.save(ledgerEntry);

                InventoryLedgerEntry destinationLedgerEntry = InventoryLedgerEntry.builder()
                                .stockItemId(request.getSkuId())
                                .eventType(InventoryLedgerEventType.PUTAWAY)
                                .changeInQuantity(request.getQuantity())
                                // TODO(CAP-215): quantityAfter requires InventoryOnHand read model; 0 is a
                                // placeholder
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

        private void ensureValidationPassed(ValidationResult validationResult) {
                if (validationResult == null) {
                        throw new IllegalStateException("Putaway validation returned no result");
                }
                if (validationResult.isValid()) {
                        return;
                }

                ValidationResult.ValidationError firstError = validationResult.getErrors().isEmpty()
                                ? null
                                : validationResult.getErrors().getFirst();
                String errorCode = firstError != null ? firstError.getErrorCode() : "PUTAWAY_VALIDATION_FAILED";
                String message = firstError != null ? firstError.getMessage()
                                : "Putaway validation failed";
                throw new PutawayValidationException(errorCode, message);
        }
}
