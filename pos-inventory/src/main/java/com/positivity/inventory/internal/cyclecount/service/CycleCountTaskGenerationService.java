package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.CycleCountTaskResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountTaskGenerationResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.GenerateCycleCountTasksRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Expands a cycle count plan into the {@code CycleCountTask}s auditors actually count against —
 * the plan → task half of the cycle count pipeline (plans and schedules on one side,
 * count/recount/adjustment on the other side, this in between).
 */
public interface CycleCountTaskGenerationService {

    /**
     * Generates {@code ASSIGNED} count tasks for the plan: one task per (storage location, SKU)
     * with positive book stock in the plan's scope, snapshotting the ledger-derived on-hand as
     * the task's expected quantity. Scope is the plan's zones (each zone plus its replicated
     * descendant storage locations) or, for a plan without zones, every replicated storage
     * location of the plan's site — falling back to the plan's own location id when no replicas
     * exist. Idempotent per (plan, bin, SKU): re-generation only tops up pairs that gained stock
     * since the last pass, and the plan row is locked for the pass so concurrent requests
     * serialize. A PLANNED plan is transitioned to STARTED once it has tasks; a pass that finds
     * no stocked pair at all leaves the plan PLANNED rather than starting an empty count.
     *
     * @throws com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException when the
     *     plan does not exist
     * @throws IllegalStateException when the plan is not in PLANNED or STARTED status
     */
    @NonNull
    CycleCountTaskGenerationResponse generateTasks(
            @NonNull UUID planId, @NonNull GenerateCycleCountTasksRequest request);

    /** Tasks generated from the plan, in creation order. */
    @NonNull
    List<CycleCountTaskResponse> getTasksForPlan(@NonNull UUID planId);
}
