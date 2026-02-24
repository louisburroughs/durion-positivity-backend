package com.positivity.inventory.internal.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.service.CycleCountPlanService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/inventory/cycleCountPlans")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('inventory:adjustment:create','inventory:availability:read')")
public class CycleCountPlanController {

    private final CycleCountPlanService cycleCountPlanService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_CREATE", apiVersion = "1")
    public ResponseEntity<CycleCountPlanResponse> createPlan(
            @RequestBody CreateCycleCountPlanRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String createdBy = userId == null || userId.isBlank() ? "system" : userId;
        CycleCountPlanResponse response = cycleCountPlanService.createPlan(request, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{planId}")
    public ResponseEntity<CycleCountPlanResponse> getPlan(@PathVariable UUID planId) {
        return ResponseEntity.ok(cycleCountPlanService.getPlan(planId));
    }
}
