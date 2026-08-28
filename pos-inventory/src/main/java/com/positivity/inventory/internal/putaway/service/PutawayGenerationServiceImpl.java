package com.positivity.inventory.internal.putaway.service;

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
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.internal.service.PutawayRuleMatcher;
import com.positivity.inventory.internal.service.StagingLocationResolver;
import java.util.List;
import java.util.Map;
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

    private final PutawayRuleMatcher putawayRuleMatcher;
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

        List<ParsedPutawayLineItem> lineItems = resolveLineItems(request);

        // One rule lookup and one category lookup for the whole receipt, but a rule resolved per
        // line: two lines of a receipt legitimately land in different bins (#1514).
        Map<UUID, PutawayRule> rulesByProduct = putawayRuleMatcher.matchAll(
                lineItems.stream().map(ParsedPutawayLineItem::productId).toList());

        List<PutawayTask> tasks = lineItems.stream()
                .map(lineItem -> toTask(sourceReceipt, lineItem, rulesByProduct.get(lineItem.productId())))
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
     * Builds an unassigned putaway task for one received line, resolving the suggested destination
     * via the line's own winning rule and that rule's destination strategy (odoo-parity K2, issue
     * #1055). A {@code FIXED} rule yields byte-identical pre-K2 behavior (fixed destination, no
     * fallback metadata).
     *
     * <p>{@code winningRule} is never null: {@link PutawayRuleMatcher} either returns a rule for
     * every product or throws {@code NoPutawayRuleMatchException}. The hardcoded
     * {@code 00000000-0000-0000-0000-000000000001} default location this method used to fall back on
     * is gone (#1514) — no environment ever had that bin, so it produced a task pointing at a
     * location that does not exist and deferred the failure to execution time. An enabled
     * {@code ANY} rule is the terminal fallback now, and its absence is reported as a configuration
     * error at once.
     */
    private PutawayTask toTask(
            GoodsReceiptEntity sourceReceipt, ParsedPutawayLineItem lineItem, PutawayRule winningRule) {
        PutawayDestinationResolver.ResolvedDestination destination =
                putawayDestinationResolver.resolve(winningRule, lineItem.productId(), lineItem.quantity());
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
