package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayLineItemRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.ReceiptNotStagedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayGenerationService;
import com.positivity.inventory.service.PutawayValidationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PutawayGenerationServiceImpl implements PutawayGenerationService {

    private static final UUID DEFAULT_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final PutawayRuleRepository putawayRuleRepository;
    private final PutawayTaskRepository putawayTaskRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final PutawayDestinationResolver putawayDestinationResolver;
    private final StagingLocationResolver stagingLocationResolver;
    private final PutawayValidationService putawayValidationService;

    @Override
    @Transactional
    public @NonNull List<PutawayTaskResponse> generateTasksForReceipt(@NonNull GeneratePutawayTasksRequest request) {
        UUID sourceReceiptId = parseRequiredUuid(request.getSourceReceiptId(), "sourceReceiptId");
        GoodsReceiptEntity sourceReceipt = goodsReceiptRepository
                .findById(sourceReceiptId)
                .orElseThrow(() -> new ResourceNotFoundException("GoodsReceipt", sourceReceiptId.toString()));

        UUID stagingLocationId = stagingLocationResolver.resolveStagingLocationId();
        if (!stagingLocationId.equals(sourceReceipt.getLocationId())) {
            throw new ReceiptNotStagedException(sourceReceiptId, sourceReceipt.getLocationId(), stagingLocationId);
        }

        List<PutawayRule> enabledRules = putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAsc();
        Optional<PutawayRule> winningRule =
                enabledRules.isEmpty() ? Optional.empty() : Optional.of(enabledRules.get(0));

        List<ParsedPutawayLineItem> lineItems = resolveLineItems(request);

        List<PutawayTask> tasks = lineItems.stream()
                .map(lineItem -> toTask(sourceReceipt, lineItem, winningRule))
                .toList();

        return putawayTaskRepository.saveAll(tasks).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PutawayTaskResponse> getTasksByReceiptId(@NonNull String receiptId) {
        UUID sourceReceiptId = parseRequiredUuid(receiptId, "receiptId");
        return putawayTaskRepository.findBySourceReceipt_ReceiptId(sourceReceiptId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PutawayTaskResponse> getAvailableTasks(
            @Nullable UUID locationId, @Nullable UUID storageLocationId) {
        UUID scopedLocationId = storageLocationId != null ? storageLocationId : locationId;
        List<PutawayTask> tasks = scopedLocationId == null
                ? putawayTaskRepository.findByStatusIn(List.of(PutawayTaskStatus.UNASSIGNED))
                : putawayTaskRepository.findByStatusInAndSourceLocationId(
                        List.of(PutawayTaskStatus.UNASSIGNED), scopedLocationId);

        return tasks.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional
    public @NonNull PutawayTaskResponse claimTask(@NonNull String taskId, @NonNull String userId) {
        UUID putawayTaskId = parseRequiredUuid(taskId, "taskId");

        PutawayTask task = putawayTaskRepository
                .findByIdForUpdate(putawayTaskId)
                .orElseThrow(() -> new TaskNotFoundException(putawayTaskId));

        task.setStatus(PutawayTaskStatus.ASSIGNED);
        task.setAssigneeId(userId);

        PutawayTask savedTask = putawayTaskRepository.save(task);
        return toResponse(savedTask);
    }

    /**
     * Builds an unassigned putaway task for one received line, resolving the
     * suggested destination via the winning rule's destination strategy
     * (odoo-parity K2, issue #1055). With no matching rule the pre-K2 default
     * location is used; a {@code FIXED} rule yields byte-identical pre-K2
     * behavior (fixed destination, no fallback metadata).
     */
    private PutawayTask toTask(
            GoodsReceiptEntity sourceReceipt, ParsedPutawayLineItem lineItem, Optional<PutawayRule> winningRule) {
        PutawayDestinationResolver.ResolvedDestination destination = winningRule
                .map(rule -> putawayDestinationResolver.resolve(rule, lineItem.productId(), lineItem.quantity()))
                .orElseGet(() -> new PutawayDestinationResolver.ResolvedDestination(DEFAULT_LOCATION, null, null));
        putawayValidationService.validateLocationCompatibility(
                destination.destinationLocationId(), lineItem.productId().toString());

        return PutawayTask.builder()
                .sourceReceipt(sourceReceipt)
                .productId(lineItem.productId())
                .quantity(lineItem.quantity())
                .sourceLocationId(sourceReceipt.getLocationId())
                .suggestedDestinationLocationId(destination.destinationLocationId())
                .originalSuggestedLocationId(destination.originalSuggestedLocationId())
                .finalSuggestedLocationId(destination.isFallback() ? destination.destinationLocationId() : null)
                .fallbackReason(destination.fallbackReason())
                .status(PutawayTaskStatus.UNASSIGNED)
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

    private List<ParsedPutawayLineItem> resolveLineItems(GeneratePutawayTasksRequest request) {
        boolean hasLineItems =
                request.getLineItems() != null && !request.getLineItems().isEmpty();
        boolean hasLegacyProductId =
                request.getProductId() != null && !request.getProductId().isBlank();
        boolean hasLegacyQuantity = request.getQuantity() != null;

        if (hasLineItems && (hasLegacyProductId || hasLegacyQuantity)) {
            throw new IllegalArgumentException("Provide either lineItems or productId/quantity, not both");
        }

        if (hasLineItems) {
            return IntStream.range(0, request.getLineItems().size())
                    .mapToObj(index -> parseLineItem(request.getLineItems().get(index), index))
                    .toList();
        }

        if (hasLegacyProductId || hasLegacyQuantity) {
            if (!hasLegacyProductId || !hasLegacyQuantity) {
                throw new IllegalArgumentException(
                        "Both productId and quantity are required when lineItems is not provided");
            }
            return List.of(new ParsedPutawayLineItem(
                    parseRequiredUuid(request.getProductId(), "productId"),
                    parseRequiredPositiveQuantity(request.getQuantity(), "quantity")));
        }

        throw new IllegalArgumentException("Either lineItems or productId/quantity is required");
    }

    private ParsedPutawayLineItem parseLineItem(PutawayLineItemRequest lineItem, int index) {
        if (lineItem == null) {
            throw new IllegalArgumentException("lineItems[" + index + "] is required");
        }
        UUID productId = parseRequiredUuid(lineItem.getProductId(), "lineItems[" + index + "].productId");
        Integer quantity = parseRequiredPositiveQuantity(lineItem.getQuantity(), "lineItems[" + index + "].quantity");
        return new ParsedPutawayLineItem(productId, quantity);
    }

    private Integer parseRequiredPositiveQuantity(Integer value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be greater than 0");
        }
        return value;
    }

    private record ParsedPutawayLineItem(UUID productId, Integer quantity) {}

    private PutawayTaskResponse toResponse(PutawayTask task) {
        return PutawayTaskResponse.builder()
                .taskId(task.getTaskId() != null ? task.getTaskId().toString() : null)
                .sourceReceiptId(
                        task.getSourceReceipt() != null
                                        && task.getSourceReceipt().getReceiptId() != null
                                ? task.getSourceReceipt().getReceiptId().toString()
                                : null)
                .productId(task.getProductId() != null ? task.getProductId().toString() : null)
                .quantity(task.getQuantity())
                .sourceLocationId(task.getSourceLocationId())
                .suggestedDestinationLocationId(task.getSuggestedDestinationLocationId())
                .originalSuggestedLocationId(task.getOriginalSuggestedLocationId())
                .finalSuggestedLocationId(task.getFinalSuggestedLocationId())
                .actualDestinationLocationId(task.getActualDestinationLocationId())
                .fallbackReason(
                        task.getFallbackReason() != null
                                ? task.getFallbackReason().name()
                                : null)
                .status(task.getStatus() != null ? task.getStatus().name() : null)
                .assigneeId(task.getAssigneeId())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}
