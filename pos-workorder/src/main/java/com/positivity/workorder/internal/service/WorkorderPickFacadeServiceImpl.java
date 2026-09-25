package com.positivity.workorder.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.config.InventoryCommandPublisher;
import com.positivity.workorder.internal.config.InventoryCommandPublisher.ConsumeLine;
import com.positivity.workorder.internal.dto.pick.CompletePickTaskRequest;
import com.positivity.workorder.internal.dto.pick.ConfirmPickLineRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsRequest;
import com.positivity.workorder.internal.dto.pick.ConsumePickedItemsResponse;
import com.positivity.workorder.internal.dto.pick.ResolveScanRequest;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse;
import com.positivity.workorder.internal.dto.pick.ResolveScanResponse.MatchStatus;
import com.positivity.workorder.internal.dto.pick.WorkorderPickListResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickTaskResponse;
import com.positivity.workorder.internal.dto.pick.WorkorderPickedItemResponse;
import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ConsumeItemStatus;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static final String STATUS_PICKED = "PICKED";

    private final ExtPickListReplicaRepository pickListReplicaRepository;
    private final ExtPickTaskReplicaRepository pickTaskReplicaRepository;
    private final ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisher;
    private final WorkorderRepository workorderRepository;

    @Override
    @NonNull
    public WorkorderPickListResponse getPickListForWorkorder(@NonNull UUID workorderId) {
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_VIEW);
        return mapPickList(resolvePrimaryPickList(workorderId));
    }

    /**
     * The workorder's pick tasks, or an empty list when it has no pick list (#1479).
     *
     * <p>404 was the wrong answer here. "This workorder has nothing to pick" is a perfectly normal
     * state — a labour-only job has no parts, and a promoted job's list exists only once
     * pos-inventory's generate command has been applied — and answering it as an error forced
     * every caller to treat a missing list as a failure. The seeder swallowed the 404 and skipped
     * picking entirely as a result, which is why simulated jobs never moved stock.
     *
     * <p>The pick-list header endpoint keeps its 404: asked for a specific resource, it either
     * exists or it does not.
     */
    @Override
    @NonNull
    public List<WorkorderPickTaskResponse> getPickTasksForWorkorder(@NonNull UUID workorderId) {
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_VIEW);
        return findPrimaryPickList(workorderId)
                .map(pickList ->
                        pickTaskReplicaRepository.findByPickListIdOrderBySortOrderAsc(pickList.getPickListId()).stream()
                                .map(task -> mapPickTask(task, task.getStatus()))
                                .toList())
                .orElseGet(List::of);
    }

    @Override
    @NonNull
    public ResolveScanResponse resolveScan(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull ResolveScanRequest request) {
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE);
        ExtPickTaskReplica task = resolveTask(workorderId, pickTaskId);

        Outcome productOutcome = resolveProductOutcome(task, request);
        Outcome locationOutcome = resolveLocationOutcome(task, request);

        boolean matched = productOutcome == Outcome.MATCH && locationOutcome == Outcome.MATCH;
        MatchStatus matchStatus;
        if (matched) {
            matchStatus = MatchStatus.MATCHED;
        } else if (productOutcome == Outcome.UNAVAILABLE) {
            matchStatus = MatchStatus.PRODUCT_CODE_UNAVAILABLE;
        } else if (locationOutcome == Outcome.UNAVAILABLE) {
            matchStatus = MatchStatus.LOCATION_CODE_UNAVAILABLE;
        } else if (productOutcome == Outcome.MISMATCH && locationOutcome == Outcome.MISMATCH) {
            matchStatus = MatchStatus.NO_MATCH;
        } else if (productOutcome == Outcome.MISMATCH) {
            matchStatus = MatchStatus.SKU_MISMATCH;
        } else {
            matchStatus = MatchStatus.LOCATION_MISMATCH;
        }

        // Echo an id-based scan back as-is; a code-based scan resolves to the task's own id only
        // once the code has actually matched — otherwise the caller does not know which sku/location
        // was really scanned, and inventing one would be worse than leaving it null.
        UUID resolvedSkuId = request.getScannedSkuId() != null
                ? request.getScannedSkuId()
                : (productOutcome == Outcome.MATCH ? task.getSkuId() : null);
        UUID resolvedLocationId = request.getScannedLocationId() != null
                ? request.getScannedLocationId()
                : (locationOutcome == Outcome.MATCH ? task.getLocationId() : null);

        return ResolveScanResponse.builder()
                .pickTaskId(task.getPickTaskId())
                .pickListId(task.getPickListId())
                .resolvedSkuId(resolvedSkuId)
                .resolvedLocationId(resolvedLocationId)
                .expectedProductCode(task.getProductCode())
                .expectedLocationCode(task.getLocationName())
                .expectedLocationBarcode(task.getLocationBarcode())
                .matched(matched)
                .matchStatus(matchStatus.name())
                .build();
    }

    /** Per-dimension scan outcome, folded into one {@link MatchStatus} in {@link #resolveScan}. */
    private enum Outcome {
        MATCH,
        MISMATCH,
        UNAVAILABLE
    }

    private static Outcome resolveProductOutcome(ExtPickTaskReplica task, ResolveScanRequest request) {
        if (request.getScannedSkuId() != null) {
            return request.getScannedSkuId().equals(task.getSkuId()) ? Outcome.MATCH : Outcome.MISMATCH;
        }
        if (task.getProductCode() == null) {
            return Outcome.UNAVAILABLE;
        }
        return equalsIgnoreCaseTrimmed(request.getScannedProductCode(), task.getProductCode())
                ? Outcome.MATCH
                : Outcome.MISMATCH;
    }

    private static Outcome resolveLocationOutcome(ExtPickTaskReplica task, ResolveScanRequest request) {
        if (request.getScannedLocationId() != null) {
            return request.getScannedLocationId().equals(task.getLocationId()) ? Outcome.MATCH : Outcome.MISMATCH;
        }
        // A location code matches either the location's barcode or its name (#2217): a mechanic
        // may scan either printed label, and the replica cannot say which one is posted where.
        if (task.getLocationBarcode() == null && task.getLocationName() == null) {
            return Outcome.UNAVAILABLE;
        }
        String scanned = request.getScannedLocationCode();
        boolean matches = equalsIgnoreCaseTrimmed(scanned, task.getLocationBarcode())
                || equalsIgnoreCaseTrimmed(scanned, task.getLocationName());
        return matches ? Outcome.MATCH : Outcome.MISMATCH;
    }

    private static boolean equalsIgnoreCaseTrimmed(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
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
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE);
        ExtPickTaskReplica task = resolveTask(workorderId, pickTaskId);
        requestConfirm(task, request.getQuantityPicked());
        return mapPickTask(task, STATUS_PENDING);
    }

    @Override
    @NonNull
    public WorkorderPickTaskResponse completePickTask(
            @NonNull UUID workorderId, @NonNull UUID pickTaskId, @NonNull CompletePickTaskRequest request) {
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_EXECUTE);
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
        requireLocationScope(workorderId, WorkorderPermissions.INVENTORY_PICK_LIST_VIEW);
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
        requireLocationScope(workorderId, WorkorderPermissions.PARTS_CONSUME);
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

        try {
            publisher().requestItemsConsume(workorderId, pickList.getPickListId(), lines);
        } catch (IllegalStateException e) {
            // Broker nack/timeout mirrors the old synchronous failure mode: 503, retryable.
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Unable to queue consumption for workorder " + workorderId, e);
        }

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
        return findPrimaryPickList(workorderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No pick list found for workorder " + workorderId));
    }

    /** The workorder's primary pick list, if it has one; see {@link #getPickTasksForWorkorder}. */
    @NonNull
    private Optional<ExtPickListReplica> findPrimaryPickList(@NonNull UUID workorderId) {
        return pickListReplicaRepository.findByWorkorderIdOrderByPickListIdAsc(workorderId).stream()
                .findFirst();
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
                .productCode(task.getProductCode())
                .storageLocationCode(task.getLocationName())
                .storageLocationBarcode(task.getLocationBarcode())
                .requiredQty(task.getQuantityRequired())
                .pickedQty(task.getQuantityPicked())
                .remainingQty(remainingQty)
                .status(status)
                .sortOrder(task.getSortOrder())
                .version(task.getAggregateVersion())
                .build();
    }

    /**
     * Gates a state-changing (or state-describing) pick-facade call to the caller's location reach
     * (ADR-0061 mechanism, #2204). Picking and consuming parts happen at the workorder's own site,
     * not the technician's assignment, so the check is against {@link Workorder#getLocationId()}
     * rather than any assignment gate.
     *
     * <p>Absent on purpose when there is nothing to scope against: a workorder this module does not
     * hold (the caller's own downstream lookup answers 404), or one whose {@code locationId} has not
     * been backfilled yet (ADR-0061 rollout is per-module and per-row). Both skip rather than fail
     * closed, matching every other location-scope gate in this module.
     */
    private void requireLocationScope(@NonNull UUID workorderId, @NonNull String permission) {
        Workorder workorder = workorderRepository.findById(workorderId).orElse(null);
        if (workorder == null) {
            log.debug("Skipping location scope check for workorder {}: workorder not found", workorderId);
            return;
        }
        if (workorder.getLocationId() == null) {
            log.debug("Skipping location scope check for workorder {}: no locationId recorded", workorderId);
            return;
        }
        SecurityContextHelper.locationScope().require(permission, workorder.getLocationId());
    }
}
