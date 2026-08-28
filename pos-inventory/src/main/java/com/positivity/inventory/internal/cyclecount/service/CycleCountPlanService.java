package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface CycleCountPlanService {

    @NonNull
    CycleCountPlanResponse createPlan(@NonNull CreateCycleCountPlanRequest request, @NonNull String createdBy);

    /**
     * Creates a plan on behalf of a recurring schedule (odoo-parity I1, #1031).
     * Unlike {@link #createPlan}, the scheduled date is the (possibly past)
     * due date, zones may be empty, and the plan records its provenance
     * (scheduleId + dueDate) for the exactly-once guard and the completion
     * restamp.
     */
    @NonNull
    CycleCountPlanResponse createScheduledPlan(
            @NonNull UUID scheduleId,
            @NonNull UUID locationId,
            @NonNull List<UUID> zoneIds,
            @NonNull LocalDate dueDate,
            @NonNull String createdBy);

    /**
     * Transitions a plan to a new lifecycle status, validating the move
     * (PLANNED → STARTED/CANCELLED, STARTED → COMPLETED_PENDING_APPROVAL/
     * CANCELLED, COMPLETED_PENDING_APPROVAL → APPROVED/REJECTED). Approving a
     * schedule-created plan restamps the schedule's next due date from the
     * completion date (Odoo's last/next-inventory-date loop).
     */
    @NonNull
    CycleCountPlanResponse updateStatus(@NonNull UUID planId, @NonNull CycleCountPlanStatus newStatus);

    @NonNull
    CycleCountPlanResponse getPlan(@NonNull UUID planId);

    /**
     * Lists one page of cycle count plans, newest first, optionally filtered by
     * location and/or status. Null filters are ignored.
     */
    @NonNull
    List<CycleCountPlanResponse> listPlans(UUID locationId, CycleCountPlanStatus status, int page, int size);
}
