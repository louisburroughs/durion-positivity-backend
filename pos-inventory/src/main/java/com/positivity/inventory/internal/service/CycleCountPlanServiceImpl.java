package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.service.CycleCountPlanService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CycleCountPlanServiceImpl implements CycleCountPlanService {

    private final CycleCountPlanRepository cycleCountPlanRepository;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull CycleCountPlanResponse createPlan(
            @NonNull CreateCycleCountPlanRequest request, @NonNull String createdBy) {
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
    public @NonNull CycleCountPlanResponse getPlan(@NonNull UUID planId) {
        return cycleCountPlanRepository
                .findById(planId)
                .map(this::toResponse)
                .orElseThrow(() -> new CycleCountPlanNotFoundException(planId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CycleCountPlanResponse> listPlans(
            UUID locationId, CycleCountPlanStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return cycleCountPlanRepository.findByOptionalFilters(locationId, status, pageable).getContent().stream()
                .map(this::toResponse)
                .toList();
    }

    private void validate(CreateCycleCountPlanRequest request) {
        if (request.getLocationId() == null) {
            throw new IllegalArgumentException("locationId is required");
        }
        if (request.getZoneIds() == null || request.getZoneIds().isEmpty()) {
            throw new IllegalArgumentException("zoneIds cannot be empty");
        }
        if (request.getScheduledDate() == null || !request.getScheduledDate().isAfter(LocalDate.now(clock))) {
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
