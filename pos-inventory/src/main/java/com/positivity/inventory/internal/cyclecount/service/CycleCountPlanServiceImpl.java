package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountScheduleRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CycleCountPlanServiceImpl implements CycleCountPlanService {

    /** Allowed lifecycle moves; statuses absent from the map are terminal. */
    private static final Map<CycleCountPlanStatus, Set<CycleCountPlanStatus>> ALLOWED_TRANSITIONS = Map.of(
            CycleCountPlanStatus.PLANNED, Set.of(CycleCountPlanStatus.STARTED, CycleCountPlanStatus.CANCELLED),
            CycleCountPlanStatus.STARTED,
                    Set.of(CycleCountPlanStatus.COMPLETED_PENDING_APPROVAL, CycleCountPlanStatus.CANCELLED),
            CycleCountPlanStatus.COMPLETED_PENDING_APPROVAL,
                    Set.of(CycleCountPlanStatus.APPROVED, CycleCountPlanStatus.REJECTED));

    private final CycleCountPlanRepository cycleCountPlanRepository;
    private final CycleCountScheduleRepository cycleCountScheduleRepository;
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
    @Transactional
    public @NonNull CycleCountPlanResponse createScheduledPlan(
            @NonNull UUID scheduleId,
            @NonNull UUID locationId,
            @NonNull List<UUID> zoneIds,
            @NonNull LocalDate dueDate,
            @NonNull String createdBy) {
        CycleCountPlan saved = cycleCountPlanRepository.save(CycleCountPlan.builder()
                .locationId(locationId)
                .zoneIds(zoneIds)
                .planName("Scheduled count — " + locationId + " — " + dueDate)
                .scheduledDate(dueDate)
                .status(CycleCountPlanStatus.PLANNED)
                .scheduleId(scheduleId)
                .dueDate(dueDate)
                .createdBy(createdBy)
                .build());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public @NonNull CycleCountPlanResponse updateStatus(@NonNull UUID planId, @NonNull CycleCountPlanStatus newStatus) {
        CycleCountPlan plan = cycleCountPlanRepository
                .findById(planId)
                .orElseThrow(() -> new CycleCountPlanNotFoundException(planId));

        if (!ALLOWED_TRANSITIONS.getOrDefault(plan.getStatus(), Set.of()).contains(newStatus)) {
            throw new IllegalStateException(
                    "Invalid cycle count plan status transition: " + plan.getStatus() + " -> " + newStatus);
        }

        plan.setStatus(newStatus);
        CycleCountPlan saved = cycleCountPlanRepository.save(plan);

        if (newStatus == CycleCountPlanStatus.APPROVED && saved.getScheduleId() != null) {
            restampScheduleAfterCompletion(saved);
        }

        return toResponse(saved);
    }

    @Override
    @Transactional
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_STATUS_UPDATE", apiVersion = "1")
    public @NonNull CycleCountPlanResponse startForTaskGeneration(@NonNull UUID planId) {
        return updateStatus(planId, CycleCountPlanStatus.STARTED);
    }

    /**
     * Odoo's last/next-inventory-date loop (odoo-parity I1, #1031): approving a
     * schedule-created plan restamps the schedule's next due date from the
     * completion date + frequencyDays, so a late count does not immediately
     * come due again.
     */
    private void restampScheduleAfterCompletion(CycleCountPlan plan) {
        cycleCountScheduleRepository
                .findById(plan.getScheduleId())
                .ifPresentOrElse(
                        schedule -> {
                            LocalDate completionDate = LocalDate.now(clock);
                            LocalDate restamped = completionDate.plusDays(schedule.getFrequencyDays());
                            log.info(
                                    "Cycle count plan {} approved; restamping schedule {} nextDueDate {} -> {}",
                                    plan.getPlanId(),
                                    schedule.getScheduleId(),
                                    schedule.getNextDueDate(),
                                    restamped);
                            schedule.setNextDueDate(restamped);
                            cycleCountScheduleRepository.save(schedule);
                        },
                        () -> log.warn(
                                "Cycle count plan {} references missing schedule {}; skipping restamp",
                                plan.getPlanId(),
                                plan.getScheduleId()));
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
                .scheduleId(plan.getScheduleId())
                .dueDate(plan.getDueDate())
                .createdBy(plan.getCreatedBy())
                .createdAt(plan.getCreatedAt())
                .updatedAt(plan.getUpdatedAt())
                .build();
    }
}
