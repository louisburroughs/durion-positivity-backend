package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import java.util.UUID;

public interface CycleCountPlanService {

    CycleCountPlanResponse createPlan(CreateCycleCountPlanRequest request, String createdBy);

    CycleCountPlanResponse getPlan(UUID planId);
}
