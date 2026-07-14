package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.config.InventoryCommandPublisher.ConsumeLine;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.enums.ConsumeItemStatus;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.service.WorkorderPickFacadeService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Workorder pick facade over the event-fed pick replicas (ADR-0044 §4/§6, #901 Phase 5.5).
 *
 * <p>Reads answer from {@code ext_pick_list} / {@code ext_pick_task}, fed by
 * {@code inventory.events.v1} ({@link InventoryEventsListener}). Writes (confirm, consume)
 * publish commands on {@code inventory.commands.v1} via {@link InventoryCommandPublisher} and
 * return a {@code PENDING} view of the replica state — the result fact updates the replica and
 * subsequent reads observe it. This replaces the retired synchronous
 * {@code InventoryPickClient}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkorderPickFacadeServiceImpl implements WorkorderPickFacadeService {

    private static final String STATUS_MATCHED = "MATCHED";
    private static final String STATUS_LOCATION_MISMATCH = "LOCATION_MISMATCH";
    private static final String STATUS_SKU_MISMATCH = "SKU_MISMATCH";
    private static final String STATUS_NO_MATCH = "NO_MATCH";
    private static final String STATUS_PICKED = "PICKED";

    private final ExtPickListReplicaRepository pickListReplicaRepository;
    private final ExtPickTaskReplicaRepository pickTaskReplicaRepository;
    private final ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher;

    @Override
    @NonNull
    public WorkorderPickListResponse getPickListForWorkorder(@NonNull UUID workorderId) {
        return mapPickList(resolvePrimaryPickList(workorderId));
    }

    @Override
    @NonNull
    public List<WorkorderPickTaskResponse> getPickTasksForWorkorder(@NonNull UUID workorderId) {
        ExtPickListReplica pickList = resolvePrimaryPickList(workorderId);
        return pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(pickList.getPickListId()).stream()
                .map(task -> mapPickTask(task, task.getStatus()))
                .toList();
    }

    @Override
    @NonNull
    public ResolveScanResponse resolveScan(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull ResolveScanRequest request) {
        ExtPickTaskReplica task = resolveTask(workorderId, pickTaskId);

        boolean skuMatches = task.getSkuId() != null && task.getSkuId().equals(request.getScannedSkuId());
        boolean locationMatches =
                task.getLocationId() != null && task.getLocationId().equals(request.getScannedLocationId());

        boolean matched = skuMatches && locationMatches;
        String matchStatus;
        if (matched) {
            matchStatus = STATUS_MATCHED;
        } else if (skuMatches) {
            matchStatus = STATUS_LOCATION_MISMATCH;
        } else if (locationMatches) {
            matchStatus = STATUS_SKU_MISMATCH;
        } else {
            matchStatus = STATUS_NO_MATCH;
        }

        return ResolveScanResponse.builder()
                .pickTaskId(task.getPickTaskId())
                .pickListId(task.getPickListId())
                .resolvedSkuId(request.getScannedSkuId())
                .resolvedLocationId(request.getScannedLocationId())
                .matched(matched)
                .matchStatus(matchStatus)
                .build();
    }

    @Override
    @NonNull
    public WorkorderPickTaskResponse confirmPickLine(
            @NonNull UUID workorderId,
            @NonNull UUID pickTaskId,
            @NonNull UUID pickLineId,
            @NonNull ConfirmPickLineRequest request) {
        if (!pickLineId.equals(pickTaskId)) {
            // Inventory tasks are the atomic unit; facade pickLineId aliases pickTaskId.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "pickLineId " + pickLineId + " does not match pickTaskId " + pickTaskId);
        }
        ExtPickTaskReplica task = resolveTask(workorderId, pickTaskId);
        requestConfirm(task, request.getQuantityPicked());
        return mapPickTask(task, STATUS_PENDING);
    }

    @Override
    @NonNull
    public WorkorderPickTaskResponse completePickTask(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull CompletePickTaskRequest request) {
        ExtPickTaskReplica task = resolveTask(workorderId, pickTaskId);

        int remaining = task.getQuantityRequired() - task.getQuantityPicked();
        if (remaining <= 0) {
            return mapPickTask(task, task.getStatus());
        }

        // Send total required quantity so pos-inventory sets quantityPicked = quantityRequired,
        // completing the task (see #901; sending only `remaining` would under-report picks).
        requestConfirm(task, task.getQuantityRequired());
        return mapPickTask(task, STATUS_PENDING);
    }

    @Override
    @NonNull
    public List<WorkorderPickedItemResponse> getPickedItemsForWorkorder(@NonNull UUID workorderId) {
        List<ExtPickListReplica> pickLists =
                pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(workorderId);
        if (pickLists.isEmpty()) {
            return List.of();
        }

        ExtPickListReplica pickList = pickLists.getFirst();
        return pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(pickList.getPickListId()).stream()
                .filter(task -> STATUS_PICKED.equalsIgnoreCase(task.getStatus()) || task.getQuantityPicked() > 0)
                .map(task -> WorkorderPickedItemResponse.builder()
                        .pickTaskId(task.getPickTaskId())
                        .pickListId(task.getPickListId())
                        .skuId(task.getSkuId())
                        .qtyPicked(task.getQuantityPicked())
                        .qtyConsumed(task.getQuantityConsumed())
                        .qtyRemaining(Math.max(0, task.getQuantityPicked() - task.getQuantityConsumed()))
                        .status(task.getStatus())
                        .build())
                .toList();
    }

    @Override
    @NonNull
    public ConsumePickedItemsResponse consumePickedItems(
            @NonNull UUID workorderId, @NonNull ConsumePickedItemsRequest request) {
        ExtPickListReplica pickList = resolvePrimaryPickList(workorderId);
        List<ExtPickTaskReplica> tasks =
                pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(pickList.getPickListId());
        Map<UUID, ExtPickTaskReplica> taskMap =
                tasks.stream().collect(Collectors.toMap(ExtPickTaskReplica::getPickTaskId, t -> t));

        List<ConsumeLine> lines = request.getItems().stream()
                .map(item -> {
                    ExtPickTaskReplica task = taskMap.get(item.getPickTaskId());
                    if (task == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Pick task not found: " + item.getPickTaskId());
                    }
                    return new ConsumeLine(item.getPickTaskId(), task.getSkuId(), item.getQuantityToConsume());
                })
                .toList();

        publisher().requestItemsConsume(workorderId, pickList.getPickListId(), lines);

        List<ConsumePickedItemsResponse.ConsumedItemResult> results = request.getItems().stream()
                .map(item -> ConsumePickedItemsResponse.ConsumedItemResult.builder()
                        .pickTaskId(item.getPickTaskId())
                        .quantityConsumed(item.getQuantityToConsume())
                        .status(ConsumeItemStatus.PENDING)
                        .build())
                .toList();

        return ConsumePickedItemsResponse.builder()
                .workorderId(workorderId)
                .totalItemsConsumed(0)
                .results(results)
                .build();
    }

    private void requestConfirm(@NonNull ExtPickTaskReplica task, int quantityPicked) {
        if (task.getPickListId() == null || task.getSkuId() == null || task.getLocationId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Pick task " + task.getPickTaskId() + " replica is not complete enough to confirm yet");
        }
        try {
            publisher()
                    .requestPickTaskConfirm(
                            task.getPickListId(),
                            task.getPickTaskId(),
                            task.getSkuId(),
                            task.getLocationId(),
                            quantityPicked);
        } catch (IllegalStateException e) {
            // Broker nack/timeout mirrors the old synchronous failure mode: 503, retryable.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unable to queue pick confirmation for task " + task.getPickTaskId(),
                    e);
        }
    }

    @NonNull
    private InventoryCommandPublisher publisher() {
        InventoryCommandPublisher publisher = inventoryCommandPublisher.getIfAvailable();
        if (publisher == null) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "The pick workflow is asynchronous (ADR-0044 #901) and requires the Kafka event feed;"
                            + " enable workorder.kafka.enabled");
        }
        return publisher;
    }

    @NonNull
    private ExtPickListReplica resolvePrimaryPickList(@NonNull UUID workorderId) {
        List<ExtPickListReplica> pickLists =
                pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(workorderId);
        if (pickLists.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No pick list found for workorder " + workorderId);
        }
        return pickLists.getFirst();
    }

    @NonNull
    private ExtPickTaskReplica resolveTask(@NonNull UUID workorderId, @NonNull UUID pickTaskId) {
        ExtPickListReplica pickList = resolvePrimaryPickList(workorderId);
        return pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(pickList.getPickListId()).stream()
                .filter(task -> task.getPickTaskId().equals(pickTaskId))
                .findFirst()
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pick task not found: " + pickTaskId));
    }

    private WorkorderPickListResponse mapPickList(ExtPickListReplica pickList) {
        return WorkorderPickListResponse.builder()
                .pickListId(pickList.getPickListId())
                .workorderId(pickList.getWorkorderId())
                .status(pickList.getStatus())
                .priority(pickList.getPriority())
                .dueAt(pickList.getDueAt())
                .createdAt(pickList.getPickListCreatedAt())
                .updatedAt(pickList.getUpdatedAt())
                .build();
    }

    private WorkorderPickTaskResponse mapPickTask(ExtPickTaskReplica task, String status) {
        int remainingQty = task.getQuantityRequired() - task.getQuantityPicked();
        return WorkorderPickTaskResponse.builder()
                .pickTaskId(task.getPickTaskId())
                .pickListId(task.getPickListId())
                .skuId(task.getSkuId())
                .locationId(task.getLocationId())
                .requiredQty(task.getQuantityRequired())
                .pickedQty(task.getQuantityPicked())
                .remainingQty(remainingQty)
                .status(status)
                .sortOrder(task.getSortOrder())
                .version(task.getAggregateVersion())
                .build();
    }
}
