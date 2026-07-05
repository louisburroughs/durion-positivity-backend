package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface CycleCountPlanService {

    @NonNull
    CycleCountPlanResponse createPlan(@NonNull CreateCycleCountPlanRequest request, @NonNull String createdBy);

    @NonNull
    CycleCountPlanResponse getPlan(@NonNull UUID planId);

    /**
     * Lists one page of cycle count plans, newest first, optionally filtered by
     * location and/or status. Null filters are ignored.
     */
    @NonNull
    List<CycleCountPlanResponse> listPlans(UUID locationId, CycleCountPlanStatus status, int page, int size);
}
