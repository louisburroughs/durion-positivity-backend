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

    /**
     * Ceiling on how many missed boundaries one pass clears for a single schedule.
     *
     * <p>One pass drains every boundary a schedule has crossed, because the pass rate is real time
     * while the due dates are clock time: under the accelerated profile a year of weekly boundaries
     * elapses in hours, and advancing one due date per pass would silently skip nearly all of them.
     * The ceiling keeps a pathologically overdue schedule (say a daily one untouched for years) from
     * creating unbounded work in a single transaction; whatever is left stays due and is picked up
     * by the next pass.
     */
    static final int MAX_CATCH_UP_BOUNDARIES_PER_PASS = 366;

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

        ScheduleRunTally tally = new ScheduleRunTally();
        for (CycleCountSchedule schedule : due) {
            processDueSchedule(schedule, today, tally);
        }

        if (!due.isEmpty()) {
            log.info(
                    "Cycle count schedule pass: schedulesDue={} plansCreated={} plansSkippedExisting={}",
                    due.size(),
                    tally.plansCreated,
                    tally.plansSkippedExisting);
        }
        return CycleCountScheduleRunResultResponse.builder()
                .schedulesDue(due.size())
                .plansCreated(tally.plansCreated)
                .plansSkippedExisting(tally.plansSkippedExisting)
                .build();
    }

    /**
     * Clears every boundary one due schedule has crossed (up to the catch-up ceiling), creating
     * or counting plans against {@code tally} and advancing {@code nextDueDate} past whatever
     * this pass handled. A view-only schedule or a corrupt non-positive frequency is left
     * untouched instead.
     */
    private void processDueSchedule(
            @NonNull CycleCountSchedule schedule, @NonNull LocalDate today, @NonNull ScheduleRunTally tally) {
        if (!schedule.isAutoCreatePlan()) {
            // View-only schedule: it surfaces via the due view (listSchedules
            // dueOnly=true); nothing is created and nextDueDate stays put so
            // it remains visible until someone acts on it.
            return;
        }

        int frequencyDays = schedule.getFrequencyDays();
        if (frequencyDays < 1) {
            // Corrupt row: advancing by a non-positive frequency would never terminate.
            log.error(
                    "Cycle count schedule {} has a non-positive frequencyDays={}; skipping it",
                    schedule.getScheduleId(),
                    frequencyDays);
            return;
        }

        LocalDate nextDueDate = clearCrossedBoundaries(schedule, frequencyDays, today, tally);

        // Advance in the same transaction as the plan creation, past every boundary this pass
        // cleared, so a completed pass leaves the schedule not-due for the dates it handled.
        schedule.setNextDueDate(nextDueDate);
        scheduleRepository.save(schedule);

        if (!nextDueDate.isAfter(today)) {
            log.warn(
                    "Cycle count schedule {} hit the per-pass catch-up ceiling of {}; next due date is {}"
                            + " and the remaining boundaries are left for the next pass",
                    schedule.getScheduleId(),
                    MAX_CATCH_UP_BOUNDARIES_PER_PASS,
                    nextDueDate);
        }
    }

    /**
     * Creates a plan for every boundary crossed (counting one already covered by an existing
     * plan instead), stopping at the catch-up ceiling, and returns the due date the schedule
     * should advance to.
     */
    private LocalDate clearCrossedBoundaries(
            @NonNull CycleCountSchedule schedule,
            int frequencyDays,
            @NonNull LocalDate today,
            @NonNull ScheduleRunTally tally) {
        LocalDate dueDate = schedule.getNextDueDate();
        int boundariesCleared = 0;
        while (!dueDate.isAfter(today) && boundariesCleared < MAX_CATCH_UP_BOUNDARIES_PER_PASS) {
            if (planRepository.existsByScheduleIdAndDueDate(schedule.getScheduleId(), dueDate)) {
                tally.plansSkippedExisting++;
            } else {
                planService.createScheduledPlan(
                        schedule.getScheduleId(),
                        schedule.getLocationId(),
                        schedule.getZoneId() == null ? List.of() : List.of(schedule.getZoneId()),
                        dueDate,
                        SCHEDULER_ACTOR);
                tally.plansCreated++;
            }
            dueDate = dueDate.plusDays(frequencyDays);
            boundariesCleared++;
        }
        return dueDate;
    }

    /** Mutable per-pass counters {@link #runDueSchedules()} reports on {@code CycleCountScheduleRunResultResponse}. */
    private static final class ScheduleRunTally {
        private int plansCreated;
        private int plansSkippedExisting;
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
