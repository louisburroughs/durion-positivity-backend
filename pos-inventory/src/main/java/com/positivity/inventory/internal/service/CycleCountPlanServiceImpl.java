package com.positivity.inventory.internal.service;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.service.CycleCountPlanService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CycleCountPlanServiceImpl implements CycleCountPlanService {

    private final CycleCountPlanRepository cycleCountPlanRepository;

    @Override
    @Transactional
    public CycleCountPlanResponse createPlan(CreateCycleCountPlanRequest request, String createdBy) {
        validate(request);

        CycleCountPlan saved = cycleCountPlanRepository.save(CycleCountPlan.builder()
                .locationId(request.getLocationId())
                .zoneIds(request.getZoneIds())
                .planName(request.getPlanName())
                .scheduledDate(request.getScheduledDate())
                .status(CycleCountPlanStatus.PLANNED)
                .createdBy(createdBy)
                .build());

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CycleCountPlanResponse getPlan(UUID planId) {
        return cycleCountPlanRepository.findById(planId)
                .map(this::toResponse)
                .orElseThrow(() -> new CycleCountPlanNotFoundException(planId));
    }

    private void validate(CreateCycleCountPlanRequest request) {
        if (request.getLocationId() == null) {
            throw new IllegalArgumentException("locationId is required");
        }
        if (request.getZoneIds() == null || request.getZoneIds().isEmpty()) {
            throw new IllegalArgumentException("zoneIds cannot be empty");
        }
        if (request.getScheduledDate() == null || !request.getScheduledDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("scheduledDate cannot be in the past and must be in the future");
        }
    }

    private CycleCountPlanResponse toResponse(CycleCountPlan plan) {
        return CycleCountPlanResponse.builder()
                .planId(plan.getPlanId())
                .locationId(plan.getLocationId())
                .zoneIds(plan.getZoneIds())
                .planName(plan.getPlanName())
                .scheduledDate(plan.getScheduledDate())
                .status(plan.getStatus().name())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }
}
