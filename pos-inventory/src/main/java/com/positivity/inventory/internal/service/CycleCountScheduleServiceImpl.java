package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.cyclecount.schedule.CreateCycleCountScheduleRequest;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleRunResultResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.UpdateCycleCountScheduleRequest;
import com.positivity.inventory.internal.entity.CycleCountSchedule;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountScheduleRepository;
import com.positivity.inventory.service.CycleCountPlanService;
import com.positivity.inventory.service.CycleCountScheduleService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring cycle-count schedules (odoo-parity I1, issue #1031).
 *
 * <p>Exactly-once plan creation per (schedule, dueDate) is guaranteed twice
 * over: {@link #runDueSchedules()} advances {@code nextDueDate} in the same
 * transaction that creates the plan (so a completed pass makes the schedule
 * not-due), and the plan table carries a (schedule_id, due_date) unique
 * constraint as a hard backstop.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CycleCountScheduleServiceImpl implements CycleCountScheduleService {

    /** Actor recorded on plans created by the scheduled runner (no user context). */
    static final String SCHEDULER_ACTOR = "system";

    private final CycleCountScheduleRepository scheduleRepository;
    private final CycleCountPlanRepository planRepository;
    private final CycleCountPlanService planService;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull CycleCountScheduleResponse createSchedule(
            @NonNull CreateCycleCountScheduleRequest request, @NonNull String createdBy) {
        if (request.getFrequencyDays() == null || request.getFrequencyDays() <= 0) {
            throw new IllegalArgumentException("frequencyDays must be positive");
        }
        if (request.getLocationId() == null) {
            throw new IllegalArgumentException("locationId is required");
        }
        if (request.getNextDueDate() == null) {
            throw new IllegalArgumentException("nextDueDate is required");
        }

        CycleCountSchedule saved = scheduleRepository.save(CycleCountSchedule.builder()
                .locationId(request.getLocationId())
                .zoneId(request.getZoneId())
                .skuCategory(request.getSkuCategory())
                .frequencyDays(request.getFrequencyDays())
                .nextDueDate(request.getNextDueDate())
                .autoCreatePlan(Boolean.TRUE.equals(request.getAutoCreatePlan()))
                .active(true)
                .createdBy(createdBy)
                .build());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull CycleCountScheduleResponse getSchedule(@NonNull UUID scheduleId) {
        return toResponse(require(scheduleId));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<CycleCountScheduleResponse> listSchedules(
            UUID locationId, Boolean active, boolean dueOnly, int page, int size) {
        LocalDate dueOnOrBefore = dueOnly ? LocalDate.now(clock) : null;
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return scheduleRepository
                .findByOptionalFilters(locationId, active, dueOnOrBefore, pageable)
                .getContent()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public @NonNull CycleCountScheduleResponse updateSchedule(
            @NonNull UUID scheduleId, @NonNull UpdateCycleCountScheduleRequest request) {
        CycleCountSchedule schedule = require(scheduleId);

        if (request.getFrequencyDays() != null) {
            if (request.getFrequencyDays() <= 0) {
                throw new IllegalArgumentException("frequencyDays must be positive");
            }
            schedule.setFrequencyDays(request.getFrequencyDays());
        }
        if (request.getZoneId() != null) {
            schedule.setZoneId(request.getZoneId());
        }
        if (request.getSkuCategory() != null) {
            schedule.setSkuCategory(request.getSkuCategory());
        }
        if (request.getNextDueDate() != null) {
            schedule.setNextDueDate(request.getNextDueDate());
        }
        if (request.getAutoCreatePlan() != null) {
            schedule.setAutoCreatePlan(request.getAutoCreatePlan());
        }
        if (request.getActive() != null) {
            schedule.setActive(request.getActive());
        }
        return toResponse(scheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public @NonNull CycleCountScheduleResponse deactivateSchedule(@NonNull UUID scheduleId) {
        CycleCountSchedule schedule = require(scheduleId);
        schedule.setActive(false);
        return toResponse(scheduleRepository.save(schedule));
    }

    @Override
    @Transactional
    public @NonNull CycleCountScheduleRunResultResponse runDueSchedules() {
        LocalDate today = LocalDate.now(clock);
        List<CycleCountSchedule> due =
                scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(today);

        int plansCreated = 0;
        int plansSkippedExisting = 0;
        for (CycleCountSchedule schedule : due) {
            if (!schedule.isAutoCreatePlan()) {
                // View-only schedule: it surfaces via the due view (listSchedules
                // dueOnly=true); nothing is created and nextDueDate stays put so
                // it remains visible until someone acts on it.
                continue;
            }

            LocalDate dueDate = schedule.getNextDueDate();
            if (planRepository.existsByScheduleIdAndDueDate(schedule.getScheduleId(), dueDate)) {
                plansSkippedExisting++;
            } else {
                planService.createScheduledPlan(
                        schedule.getScheduleId(),
                        schedule.getLocationId(),
                        schedule.getZoneId() == null ? List.of() : List.of(schedule.getZoneId()),
                        dueDate,
                        SCHEDULER_ACTOR);
                plansCreated++;
            }
            // Advance in the same transaction as the plan creation; an overdue
            // schedule catches up one due date per pass.
            schedule.setNextDueDate(dueDate.plusDays(schedule.getFrequencyDays()));
            scheduleRepository.save(schedule);
        }

        if (!due.isEmpty()) {
            log.info(
                    "Cycle count schedule pass: schedulesDue={} plansCreated={} plansSkippedExisting={}",
                    due.size(),
                    plansCreated,
                    plansSkippedExisting);
        }
        return CycleCountScheduleRunResultResponse.builder()
                .schedulesDue(due.size())
                .plansCreated(plansCreated)
                .plansSkippedExisting(plansSkippedExisting)
                .build();
    }

    private CycleCountSchedule require(UUID scheduleId) {
        return scheduleRepository
                .findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Cycle count schedule", scheduleId.toString()));
    }

    private CycleCountScheduleResponse toResponse(CycleCountSchedule schedule) {
        return CycleCountScheduleResponse.builder()
                .scheduleId(schedule.getScheduleId())
                .locationId(schedule.getLocationId())
                .zoneId(schedule.getZoneId())
                .skuCategory(schedule.getSkuCategory())
                .frequencyDays(schedule.getFrequencyDays())
                .nextDueDate(schedule.getNextDueDate())
                .autoCreatePlan(schedule.isAutoCreatePlan())
                .active(schedule.isActive())
                .createdBy(schedule.getCreatedBy())
                .createdAt(schedule.getCreatedAt())
                .updatedAt(schedule.getUpdatedAt())
                .build();
    }
}
