package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.inventory.internal.cyclecount.service.CycleCountPlanService;
import com.positivity.inventory.internal.cyclecount.service.CycleCountScheduleService;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CreateCycleCountScheduleRequest;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleRunResultResponse;
import com.positivity.inventory.internal.entity.CycleCountPlan;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountScheduleRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end recurrence tests for recurring cycle-count schedules
 * (odoo-parity I1, issue #1031): exactly-once plan creation per due date,
 * nextDueDate advancement, the due-for-count view for autoCreate-off
 * schedules, and the completion restamp loop.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Cycle count schedule recurrence")
class CycleCountScheduleRecurrenceTest {

    @Autowired
    private CycleCountScheduleService scheduleService;

    @Autowired
    private CycleCountPlanService planService;

    @Autowired
    private CycleCountScheduleRepository scheduleRepository;

    @Autowired
    private CycleCountPlanRepository planRepository;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanSlate() {
        planRepository.deleteAll();
        scheduleRepository.deleteAll();
    }

    private CycleCountScheduleResponse createSchedule(boolean autoCreate, LocalDate nextDueDate, int frequencyDays) {
        return scheduleService.createSchedule(
                CreateCycleCountScheduleRequest.builder()
                        .locationId(UUID.randomUUID())
                        .zoneId(UUID.randomUUID())
                        .frequencyDays(frequencyDays)
                        .nextDueDate(nextDueDate)
                        .autoCreatePlan(autoCreate)
                        .build(),
                "recurrence-test");
    }

    @Test
    void dueAutoCreateSchedule_createsExactlyOnePlan_evenAcrossRepeatedRuns() {
        LocalDate today = LocalDate.now(clock);
        CycleCountScheduleResponse schedule = createSchedule(true, today, 30);

        CycleCountScheduleRunResultResponse first = scheduleService.runDueSchedules();
        CycleCountScheduleRunResultResponse second = scheduleService.runDueSchedules();

        assertThat(first.getPlansCreated()).isEqualTo(1);
        // After the first pass nextDueDate is advanced, so the schedule is no
        // longer due — the repeat run must be a no-op.
        assertThat(second.getSchedulesDue()).isZero();
        assertThat(second.getPlansCreated()).isZero();

        List<CycleCountPlan> plans = planRepository.findAll();
        assertThat(plans).hasSize(1);
        CycleCountPlan plan = plans.getFirst();
        assertThat(plan.getScheduleId()).isEqualTo(schedule.getScheduleId());
        assertThat(plan.getDueDate()).isEqualTo(today);
        assertThat(plan.getScheduledDate()).isEqualTo(today);
        assertThat(plan.getStatus()).isEqualTo(CycleCountPlanStatus.PLANNED);
        assertThat(plan.getZoneIds()).containsExactly(schedule.getZoneId());

        assertThat(scheduleRepository
                        .findById(schedule.getScheduleId())
                        .orElseThrow()
                        .getNextDueDate())
                .isEqualTo(today.plusDays(30));
    }

    @Test
    void dueViewOnlySchedule_appearsInDueView_withoutSideEffect() {
        LocalDate today = LocalDate.now(clock);
        CycleCountScheduleResponse schedule = createSchedule(false, today.minusDays(1), 7);

        CycleCountScheduleRunResultResponse result = scheduleService.runDueSchedules();

        assertThat(result.getSchedulesDue()).isEqualTo(1);
        assertThat(result.getPlansCreated()).isZero();
        assertThat(planRepository.findAll()).isEmpty();
        // nextDueDate untouched: the schedule stays visible until someone acts.
        assertThat(scheduleRepository
                        .findById(schedule.getScheduleId())
                        .orElseThrow()
                        .getNextDueDate())
                .isEqualTo(today.minusDays(1));

        List<CycleCountScheduleResponse> dueView = scheduleService.listSchedules(null, null, true, 0, 50);
        assertThat(dueView)
                .extracting(CycleCountScheduleResponse::getScheduleId)
                .contains(schedule.getScheduleId());
    }

    @Test
    void approvingScheduleCreatedPlan_restampsNextDueDateFromCompletion() {
        LocalDate today = LocalDate.now(clock);
        CycleCountScheduleResponse schedule = createSchedule(true, today, 14);
        scheduleService.runDueSchedules();
        CycleCountPlan plan = planRepository.findAll().getFirst();

        planService.updateStatus(plan.getPlanId(), CycleCountPlanStatus.STARTED);
        planService.updateStatus(plan.getPlanId(), CycleCountPlanStatus.COMPLETED_PENDING_APPROVAL);
        planService.updateStatus(plan.getPlanId(), CycleCountPlanStatus.APPROVED);

        // Restamped from the completion date, not from the old due date.
        assertThat(scheduleRepository
                        .findById(schedule.getScheduleId())
                        .orElseThrow()
                        .getNextDueDate())
                .isEqualTo(today.plusDays(14));
    }

    @Test
    void deactivatedSchedule_neverFires() {
        LocalDate today = LocalDate.now(clock);
        CycleCountScheduleResponse schedule = createSchedule(true, today, 30);
        scheduleService.deactivateSchedule(schedule.getScheduleId());

        CycleCountScheduleRunResultResponse result = scheduleService.runDueSchedules();

        assertThat(result.getSchedulesDue()).isZero();
        assertThat(planRepository.findAll()).isEmpty();
    }
}
