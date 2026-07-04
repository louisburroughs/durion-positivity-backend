package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import java.util.List;
import java.util.UUID;

public interface CycleCountPlanService {

    CycleCountPlanResponse createPlan(CreateCycleCountPlanRequest request, String createdBy);

    CycleCountPlanResponse getPlan(UUID planId);

    /**
     * Lists cycle count plans, newest first, optionally filtered by location
     * and/or status. Null filters are ignored.
     */
    List<CycleCountPlanResponse> listPlans(UUID locationId, CycleCountPlanStatus status);
}
