package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayLineItemRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayGenerationService;
import java.util.List;
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

    // NOTE: These default/staging location IDs are hardcoded for simplicity, but in
    // a real implementation they would likely be configurable or determined
    // dynamically based on the receipt or warehouse context.
    private static final UUID DEFAULT_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STAGING_LOCATION = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final PutawayRuleRepository putawayRuleRepository;
    private final PutawayTaskRepository putawayTaskRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;

    @Override
    @Transactional
    public @NonNull List<PutawayTaskResponse> generateTasksForReceipt(@NonNull GeneratePutawayTasksRequest request) {
        UUID sourceReceiptId = parseRequiredUuid(request.getSourceReceiptId(), "sourceReceiptId");
        GoodsReceiptEntity sourceReceipt = goodsReceiptRepository
                .findById(sourceReceiptId)
                .orElseThrow(() -> new ResourceNotFoundException("GoodsReceipt", sourceReceiptId.toString()));

        List<PutawayRule> enabledRules = putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAsc();
        UUID suggestedDestination =
                enabledRules.isEmpty() ? DEFAULT_LOCATION : enabledRules.get(0).getDestinationLocationId();

        List<ParsedPutawayLineItem> lineItems = resolveLineItems(request);

        List<PutawayTask> tasks = lineItems.stream()
                .map(lineItem -> PutawayTask.builder()
                        .sourceReceipt(sourceReceipt)
                        .productId(lineItem.productId())
                        .quantity(lineItem.quantity())
                        .sourceLocationId(STAGING_LOCATION)
                        .suggestedDestinationLocationId(suggestedDestination)
                        .status(PutawayTaskStatus.UNASSIGNED)
                        .build())
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
