package com.positivity.inventory.service;

import java.util.UUID;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;

public interface CycleCountPlanService {

    CycleCountPlanResponse createPlan(CreateCycleCountPlanRequest request, String createdBy);

    CycleCountPlanResponse getPlan(UUID planId);
}
