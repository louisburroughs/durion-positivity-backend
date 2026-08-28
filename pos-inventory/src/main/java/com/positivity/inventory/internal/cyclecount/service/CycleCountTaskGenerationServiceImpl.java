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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * aggregation ({@code findPositiveOnHandByLocation}) at generation time. Task {@code binLocation}
 * is the storage-location UUID rendered as text — the form {@code CycleCountConflictDetector}
 * location-scopes its window queries on and {@code CycleCountAdjustmentServiceImpl} resolves for
 * variance recomputation and the posted {@code COUNT_VARIANCE_*} entry's location, so a task's
 * whole count → conflict → adjustment lifecycle runs against one consistent location scope.
 *
 * <p>Generation row-locks the plan for the pass, so concurrent requests for one plan serialize:
 * the later request observes the earlier one's tasks and skips them instead of racing the
 * (plan, bin, SKU) unique constraint into a wholesale rollback.
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
    private final CycleCountTaskResponseMapper taskResponseMapper;

    @Override
    @Transactional
    public @NonNull CycleCountTaskGenerationResponse generateTasks(
            @NonNull UUID planId, @NonNull GenerateCycleCountTasksRequest request) {
        CycleCountPlan plan = planRepository
                .findWithLockByPlanId(planId)
                .orElseThrow(() -> new CycleCountPlanNotFoundException(planId));

        if (plan.getStatus() != CycleCountPlanStatus.PLANNED && plan.getStatus() != CycleCountPlanStatus.STARTED) {
            throw new IllegalStateException("Cannot generate tasks for cycle count plan " + planId + " in status "
                    + plan.getStatus() + "; only PLANNED or STARTED plans accept task generation");
        }

        Set<UUID> countLocations = expandCountLocations(plan);

        Set<String> existingKeys = new HashSet<>();
        for (CycleCountTask existing : taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(planId)) {
            existingKeys.add(taskKey(existing.getBinLocation(), existing.getItemSku()));
        }

        List<CycleCountTask> toCreate = new ArrayList<>();
        int skippedExisting = 0;
        for (UUID location : countLocations) {
            String binLocation = location.toString();
            for (InventoryLedgerEntryRepository.LocationOnHand onHand : ledgerRepository.findPositiveOnHandByLocation(
                    location, InventoryLedgerEventType.onHandAffectingTypes())) {
                if (!existingKeys.add(taskKey(binLocation, onHand.getStockItemId()))) {
                    skippedExisting++;
                    continue;
                }
                toCreate.add(CycleCountTask.builder()
                        .planId(planId)
                        .binLocation(binLocation)
                        .itemSku(onHand.getStockItemId())
                        .expectedQuantity(onHand.getOnHandQuantity())
                        .auditorId(request.getAuditorId())
                        .status(TaskStatus.ASSIGNED)
                        .build());
            }
        }
        List<CycleCountTask> created = toCreate.isEmpty() ? List.of() : taskRepository.saveAll(toCreate);

        boolean planHasTasks = !created.isEmpty() || skippedExisting > 0;
        CycleCountPlanStatus planStatus = plan.getStatus();
        if (planStatus == CycleCountPlanStatus.PLANNED && planHasTasks) {
            planStatus = CycleCountPlanStatus.valueOf(
                    planService.startForTaskGeneration(planId).getStatus());
        }
        if (!planHasTasks) {
            // The scope resolved to no stocked (bin, SKU) pair at all. Leave the plan PLANNED —
            // flipping it to STARTED would fabricate an in-progress count with nothing to count —
            // and say so, since a 201 with zero tasks is otherwise easy to misread as success.
            log.warn(
                    "Cycle count task generation for plan {} found no stocked (location, SKU) pairs across {}"
                            + " scanned locations; plan left in {} — check the plan's zones/site against where its"
                            + " ledger stock is actually located",
                    planId,
                    countLocations.size(),
                    planStatus);
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
                .tasks(toResponses(created))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CycleCountTaskResponse> getTasksForPlan(@NonNull UUID planId) {
        if (!planRepository.existsById(planId)) {
            throw new CycleCountPlanNotFoundException(planId);
        }
        return toResponses(taskRepository.findByPlanIdOrderByCreatedAtAscTaskIdAsc(planId));
    }

    private static String taskKey(String binLocation, String itemSku) {
        return binLocation + '|' + itemSku;
    }

    /** Maps tasks with the base-UoM lookup memoized per distinct SKU instead of one call per task. */
    private List<CycleCountTaskResponse> toResponses(List<CycleCountTask> tasks) {
        Map<String, Optional<String>> uomBySku = new HashMap<>();
        return tasks.stream()
                .map(task -> taskResponseMapper.toResponse(
                        task,
                        uomBySku.computeIfAbsent(
                                        task.getItemSku(),
                                        sku -> Optional.ofNullable(baseUnitOfMeasureResolver.resolve(sku)))
                                .orElse(null)))
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
}
