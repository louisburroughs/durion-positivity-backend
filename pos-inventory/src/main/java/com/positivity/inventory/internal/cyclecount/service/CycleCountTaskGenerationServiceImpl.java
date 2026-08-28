package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountTaskGenerationResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.GenerateCycleCountTasksRequest;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.inventory.internal.enums.TaskStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountTaskRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.service.BaseUnitOfMeasureResolver;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default plan → task generation.
 *
 * <p>The expected-quantity snapshot is taken from the ledger's positive per-location on-hand
 * aggregation ({@code findPositiveOnHandByLocation}) at generation time — the same source of
 * truth the adjustment posting path aggregates against — so the conflict detector's window-delta
 * math (odoo-parity I2, #1026) lines up with it exactly: any on-hand-affecting entry recorded
 * after the task's {@code createdAt} is an interfering movement against this snapshot.
 *
 * <p>Task {@code binLocation} is the storage-location UUID rendered as text, which is precisely
 * the form {@code CycleCountConflictDetector} location-scopes its window queries on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CycleCountTaskGenerationServiceImpl implements CycleCountTaskGenerationService {

    /**
     * Ceiling on parent-link hops when expanding zones/sites to descendant storage locations.
     * Real topologies are a handful of levels deep; the cap keeps a corrupt parent cycle in the
     * replica table from looping forever (the visited-set already stops true cycles — this is a
     * belt for pathological breadth).
     */
    static final int MAX_TOPOLOGY_DEPTH = 32;

    private final CycleCountPlanRepository planRepository;
    private final CycleCountTaskRepository taskRepository;
    private final CycleCountPlanService planService;
    private final InventoryLedgerEntryRepository ledgerRepository;
    private final ExtStorageLocationReplicaRepository storageLocationRepository;
    private final BaseUnitOfMeasureResolver baseUnitOfMeasureResolver;

    @Override
    @Transactional
    public @NonNull CycleCountTaskGenerationResponse generateTasks(
            @NonNull UUID planId, @NonNull GenerateCycleCountTasksRequest request) {
        CycleCountPlan plan =
                planRepository.findById(planId).orElseThrow(() -> new CycleCountPlanNotFoundException(planId));

        if (plan.getStatus() != CycleCountPlanStatus.PLANNED && plan.getStatus() != CycleCountPlanStatus.STARTED) {
            throw new IllegalStateException("Cannot generate tasks for cycle count plan " + planId + " in status "
                    + plan.getStatus() + "; only PLANNED or STARTED plans accept task generation");
        }

        Set<UUID> countLocations = expandCountLocations(plan);

        List<CycleCountTask> created = new ArrayList<>();
        int skippedExisting = 0;
        for (UUID location : countLocations) {
            String binLocation = location.toString();
            for (InventoryLedgerEntryRepository.LocationOnHand onHand : ledgerRepository.findPositiveOnHandByLocation(
                    location, InventoryLedgerEventType.onHandAffectingTypes())) {
                if (taskRepository.existsByPlanIdAndBinLocationAndItemSku(
                        planId, binLocation, onHand.getStockItemId())) {
                    skippedExisting++;
                    continue;
                }
                created.add(taskRepository.save(CycleCountTask.builder()
                        .planId(planId)
                        .binLocation(binLocation)
                        .itemSku(onHand.getStockItemId())
                        .expectedQuantity(onHand.getOnHandQuantity())
                        .auditorId(request.getAuditorId())
                        .status(TaskStatus.ASSIGNED)
                        .build()));
            }
        }

        CycleCountPlanStatus planStatus = plan.getStatus();
        if (planStatus == CycleCountPlanStatus.PLANNED) {
            planStatus = CycleCountPlanStatus.valueOf(planService
                    .updateStatus(planId, CycleCountPlanStatus.STARTED)
                    .getStatus());
        }

        log.info(
                "Cycle count task generation for plan {}: locationsScanned={} tasksCreated={} tasksSkippedExisting={}",
                planId,
                countLocations.size(),
                created.size(),
                skippedExisting);

        return CycleCountTaskGenerationResponse.builder()
                .planId(planId)
                .planStatus(planStatus.name())
                .locationsScanned(countLocations.size())
                .tasksCreated(created.size())
                .tasksSkippedExisting(skippedExisting)
                .tasks(created.stream().map(this::toTaskResponse).toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CycleCountTaskResponse> getTasksForPlan(@NonNull UUID planId) {
        if (!planRepository.existsById(planId)) {
            throw new CycleCountPlanNotFoundException(planId);
        }
        return taskRepository.findByPlanId(planId).stream()
                .map(this::toTaskResponse)
                .toList();
    }

    /**
     * The storage locations the plan counts, in stable order: the plan's zones when it has any,
     * otherwise every replicated storage location of the plan's site — each expanded downward
     * through the replicated parent links so a zone covers its bins. A plan whose scope resolves
     * to nothing through the replicas (e.g. no {@code ext_storage_location} facts consumed yet)
     * falls back to the plan's own location id, matching the conflict detector's SKU-global
     * fallback philosophy: degrade to coarser scoping rather than silently doing nothing.
     */
    private Set<UUID> expandCountLocations(CycleCountPlan plan) {
        LinkedHashSet<UUID> seeds = new LinkedHashSet<>();
        if (plan.getZoneIds() != null && !plan.getZoneIds().isEmpty()) {
            seeds.addAll(plan.getZoneIds());
        } else {
            storageLocationRepository.findBySiteId(plan.getLocationId()).stream()
                    .map(ExtStorageLocationReplica::getStorageLocationId)
                    .forEach(seeds::add);
            if (seeds.isEmpty()) {
                seeds.add(plan.getLocationId());
            }
        }

        LinkedHashSet<UUID> expanded = new LinkedHashSet<>(seeds);
        Set<UUID> frontier = seeds;
        for (int depth = 0; depth < MAX_TOPOLOGY_DEPTH && !frontier.isEmpty(); depth++) {
            LinkedHashSet<UUID> children = new LinkedHashSet<>();
            for (ExtStorageLocationReplica child :
                    storageLocationRepository.findByParentStorageLocationIdIn(frontier)) {
                if (expanded.add(child.getStorageLocationId())) {
                    children.add(child.getStorageLocationId());
                }
            }
            frontier = children;
        }
        return expanded;
    }

    private CycleCountTaskResponse toTaskResponse(CycleCountTask task) {
        return CycleCountTaskResponse.builder()
                .taskId(task.getTaskId())
                .binLocation(task.getBinLocation())
                .itemSku(task.getItemSku())
                .itemDescription(task.getItemDescription())
                .expectedQuantity(task.getExpectedQuantity())
                .unitOfMeasure(baseUnitOfMeasureResolver.resolve(task.getItemSku()))
                .auditorId(task.getAuditorId())
                .planId(task.getPlanId())
                .status(task.getStatus())
                .latestCountEntryId(task.getLatestCountEntryId())
                .countEntriesCount(task.getCountEntriesCount())
                .createdAt(
                        task.getCreatedAt() != null
                                ? LocalDateTime.ofInstant(task.getCreatedAt(), ZoneOffset.UTC)
                                : null)
                .updatedAt(
                        task.getUpdatedAt() != null
                                ? LocalDateTime.ofInstant(task.getUpdatedAt(), ZoneOffset.UTC)
                                : null)
                .build();
    }
}
