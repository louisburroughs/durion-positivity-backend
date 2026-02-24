package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.putaway.GeneratePutawayTasksRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayTaskResponse;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.entity.PutawayTask;
import com.positivity.inventory.internal.enums.PutawayTaskStatus;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.internal.repository.PutawayTaskRepository;
import com.positivity.inventory.service.PutawayGenerationService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PutawayGenerationServiceImpl implements PutawayGenerationService {

    private static final String DEFAULT_LOCATION = "DEFAULT-LOCATION";
    private static final String STAGING_LOCATION = "STAGING";

    private final PutawayRuleRepository putawayRuleRepository;
    private final PutawayTaskRepository putawayTaskRepository;

    @Override
    @Transactional
    public @NonNull List<PutawayTaskResponse> generateTasksForReceipt(@NonNull GeneratePutawayTasksRequest request) {
        UUID sourceReceiptId = parseRequiredUuid(request.getSourceReceiptId(), "sourceReceiptId");

        List<PutawayRule> enabledRules = putawayRuleRepository.findAllByIsEnabledTrueOrderByPriorityAsc();
        String suggestedDestination = enabledRules.isEmpty()
                ? DEFAULT_LOCATION
                : enabledRules.get(0).getDestinationLocationId();

        // TODO(CAP-217): multi-line-item per receipt requires pos-receiving
        // integration; currently creates one task per request
        PutawayTask task = PutawayTask.builder()
                .sourceReceiptId(sourceReceiptId)
                .productId(UUID.fromString(request.getProductId()))
                .quantity(request.getQuantity())
                .sourceLocationId(STAGING_LOCATION)
                .suggestedDestinationLocationId(suggestedDestination)
                .status(PutawayTaskStatus.UNASSIGNED)
                .build();

        PutawayTask savedTask = putawayTaskRepository.save(task);
        return List.of(toResponse(savedTask));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PutawayTaskResponse> getTasksByReceiptId(@NonNull String receiptId) {
        UUID sourceReceiptId = parseRequiredUuid(receiptId, "receiptId");
        return putawayTaskRepository.findBySourceReceiptId(sourceReceiptId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PutawayTaskResponse> getAvailableTasks() {
        return putawayTaskRepository.findByStatusIn(List.of(PutawayTaskStatus.UNASSIGNED)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull PutawayTaskResponse claimTask(@NonNull String taskId, @NonNull String userId) {
        UUID putawayTaskId = parseRequiredUuid(taskId, "taskId");

        PutawayTask task = putawayTaskRepository.findByIdForUpdate(putawayTaskId)
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

    private PutawayTaskResponse toResponse(PutawayTask task) {
        return PutawayTaskResponse.builder()
                .taskId(task.getTaskId() != null ? task.getTaskId().toString() : null)
                .sourceReceiptId(task.getSourceReceiptId() != null ? task.getSourceReceiptId().toString() : null)
                .productId(task.getProductId() != null ? task.getProductId().toString() : null)
                .quantity(task.getQuantity())
                .sourceLocationId(task.getSourceLocationId())
                .suggestedDestinationLocationId(task.getSuggestedDestinationLocationId())
                .originalSuggestedLocationId(task.getOriginalSuggestedLocationId())
                .finalSuggestedLocationId(task.getFinalSuggestedLocationId())
                .actualDestinationLocationId(task.getActualDestinationLocationId())
                .fallbackReason(task.getFallbackReason() != null ? task.getFallbackReason().name() : null)
                .status(task.getStatus() != null ? task.getStatus().name() : null)
                .assigneeId(task.getAssigneeId())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }
}