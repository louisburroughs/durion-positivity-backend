package com.positivity.inventory.internal.cyclecount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CreateCycleCountScheduleRequest;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleRunResultResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.UpdateCycleCountScheduleRequest;
import com.positivity.inventory.internal.entity.CycleCountSchedule;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.internal.repository.CycleCountScheduleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for CycleCountScheduleServiceImpl (odoo-parity I1, issue #1031):
 * schedule CRUD validation, the due-schedule pass (exactly-once plan creation,
 * nextDueDate advancement, view-only schedules untouched), and soft deletion.
 */
@ExtendWith(MockitoExtension.class)
class CycleCountScheduleServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.now(FIXED_CLOCK);
    /** Mirrors {@code CycleCountScheduleServiceImpl.MAX_CATCH_UP_BOUNDARIES_PER_PASS}, which is
     * package-private to that module's internal package. */
    private static final int CATCH_UP_CEILING = 366;

    private static final UUID SCHEDULE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID ZONE_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Mock
    private CycleCountScheduleRepository scheduleRepository;

    @Mock
    private CycleCountPlanRepository planRepository;

    @Mock
    private CycleCountPlanService planService;

    private CycleCountScheduleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CycleCountScheduleServiceImpl(scheduleRepository, planRepository, planService, FIXED_CLOCK);
    }

    private CycleCountSchedule schedule(boolean autoCreate, LocalDate nextDueDate) {
        return CycleCountSchedule.builder()
                .scheduleId(SCHEDULE_ID)
                .locationId(LOCATION_ID)
                .zoneId(ZONE_ID)
                .frequencyDays(30)
                .nextDueDate(nextDueDate)
                .autoCreatePlan(autoCreate)
                .active(true)
                .createdBy("user-1")
                .build();
    }

    // ─── createSchedule ───────────────────────────────────────────────────────

    @Test
    void createSchedule_validRequest_savesActiveSchedule() {
        when(scheduleRepository.save(any(CycleCountSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CycleCountScheduleResponse response = service.createSchedule(
                CreateCycleCountScheduleRequest.builder()
                        .locationId(LOCATION_ID)
                        .zoneId(ZONE_ID)
                        .skuCategory("BRAKES")
                        .frequencyDays(30)
                        .nextDueDate(TODAY.plusDays(7))
                        .autoCreatePlan(true)
                        .build(),
                "user-1");

        assertThat(response.isActive()).isTrue();
        assertThat(response.getFrequencyDays()).isEqualTo(30);
        assertThat(response.getNextDueDate()).isEqualTo(TODAY.plusDays(7));
        assertThat(response.getSkuCategory()).isEqualTo("BRAKES");
        assertThat(response.getCreatedBy()).isEqualTo("user-1");
    }

    @Test
    void createSchedule_nonPositiveFrequency_isRejected() {
        CreateCycleCountScheduleRequest request = CreateCycleCountScheduleRequest.builder()
                .locationId(LOCATION_ID)
                .frequencyDays(0)
                .nextDueDate(TODAY)
                .build();

        assertThatThrownBy(() -> service.createSchedule(request, "user-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequencyDays");
        verify(scheduleRepository, never()).save(any());
    }

    // ─── runDueSchedules ──────────────────────────────────────────────────────

    /** Due auto-create schedule: one plan is created and nextDueDate advances by frequencyDays. */
    @Test
    void runDueSchedules_dueAutoCreate_createsPlanAndAdvances() {
        CycleCountSchedule due = schedule(true, TODAY);
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY)).thenReturn(false);
        when(planService.createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of(ZONE_ID)), eq(TODAY), any()))
                .thenReturn(CycleCountPlanResponse.builder().build());

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getSchedulesDue()).isEqualTo(1);
        assertThat(result.getPlansCreated()).isEqualTo(1);
        assertThat(result.getPlansSkippedExisting()).isZero();
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.plusDays(30));
        verify(scheduleRepository).save(due);
    }

    /** Idempotency: an existing plan for the due date suppresses creation but still advances. */
    @Test
    void runDueSchedules_planAlreadyExists_skipsCreationButAdvances() {
        CycleCountSchedule due = schedule(true, TODAY);
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY)).thenReturn(true);

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getPlansCreated()).isZero();
        assertThat(result.getPlansSkippedExisting()).isEqualTo(1);
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.plusDays(30));
        verify(planService, never()).createScheduledPlan(any(), any(), any(), any(), any());
    }

    /** View-only schedule (autoCreatePlan=false): counted as due, no plan, no advancement. */
    @Test
    void runDueSchedules_autoCreateOff_noSideEffect() {
        CycleCountSchedule due = schedule(false, TODAY.minusDays(2));
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getSchedulesDue()).isEqualTo(1);
        assertThat(result.getPlansCreated()).isZero();
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.minusDays(2));
        verify(planService, never()).createScheduledPlan(any(), any(), any(), any(), any());
        verify(scheduleRepository, never()).save(any());
    }

    /** A schedule without a zone filter creates a plan with no zones. */
    @Test
    void runDueSchedules_noZoneFilter_createsPlanWithEmptyZones() {
        CycleCountSchedule due = schedule(true, TODAY);
        due.setZoneId(null);
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY)).thenReturn(false);
        when(planService.createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of()), eq(TODAY), any()))
                .thenReturn(CycleCountPlanResponse.builder().build());

        service.runDueSchedules();

        verify(planService).createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of()), eq(TODAY), any());
    }

    // ─── update / deactivate ──────────────────────────────────────────────────

    @Test
    void updateSchedule_partialUpdate_leavesNullFieldsUnchanged() {
        CycleCountSchedule existing = schedule(true, TODAY.plusDays(5));
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(existing));
        when(scheduleRepository.save(any(CycleCountSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CycleCountScheduleResponse response = service.updateSchedule(
                SCHEDULE_ID,
                UpdateCycleCountScheduleRequest.builder()
                        .frequencyDays(14)
                        .autoCreatePlan(false)
                        .build());

        assertThat(response.getFrequencyDays()).isEqualTo(14);
        assertThat(response.isAutoCreatePlan()).isFalse();
        assertThat(response.getNextDueDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(response.getZoneId()).isEqualTo(ZONE_ID);
        assertThat(response.isActive()).isTrue();
    }

    @Test
    void updateSchedule_nonPositiveFrequency_isRejected() {
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule(true, TODAY)));
        UpdateCycleCountScheduleRequest request =
                UpdateCycleCountScheduleRequest.builder().frequencyDays(-1).build();

        assertThatThrownBy(() -> service.updateSchedule(SCHEDULE_ID, request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void deactivateSchedule_setsActiveFalse() {
        CycleCountSchedule existing = schedule(true, TODAY);
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(existing));
        when(scheduleRepository.save(any(CycleCountSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CycleCountScheduleResponse response = service.deactivateSchedule(SCHEDULE_ID);

        assertThat(response.isActive()).isFalse();
    }

    @Test
    void getSchedule_unknownId_throwsNotFound() {
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSchedule(SCHEDULE_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * A single pass must clear every crossed boundary, not one per pass. Under the
     * accelerated clock a year of weekly boundaries is compressed into hours, so a
     * schedule that advances one due date per pass would silently skip most of them.
     */
    @Test
    void runDueSchedules_overdueByManyPeriods_createsOnePlanPerCrossedBoundary() {
        LocalDate firstMissed = TODAY.minusDays(150);
        CycleCountSchedule due = schedule(true, firstMissed);
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(eq(SCHEDULE_ID), any()))
                .thenReturn(false);
        when(planService.createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of(ZONE_ID)), any(), any()))
                .thenReturn(CycleCountPlanResponse.builder().build());

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        // Boundaries at -150, -120, -90, -60, -30 and today, then the next one is in the future.
        assertThat(result.getPlansCreated()).isEqualTo(6);
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.plusDays(30));
        for (int offset = 150; offset >= 0; offset -= 30) {
            verify(planService)
                    .createScheduledPlan(
                            eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of(ZONE_ID)), eq(TODAY.minusDays(offset)), any());
        }
    }

    /** Catch-up stays idempotent: boundaries that already have a plan are skipped, not duplicated. */
    @Test
    void runDueSchedules_overdueWithSomePlansAlreadyCreated_skipsOnlyThoseBoundaries() {
        CycleCountSchedule due = schedule(true, TODAY.minusDays(60));
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY.minusDays(60)))
                .thenReturn(true);
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY.minusDays(30)))
                .thenReturn(false);
        when(planRepository.existsByScheduleIdAndDueDate(SCHEDULE_ID, TODAY)).thenReturn(false);
        when(planService.createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of(ZONE_ID)), any(), any()))
                .thenReturn(CycleCountPlanResponse.builder().build());

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getPlansCreated()).isEqualTo(2);
        assertThat(result.getPlansSkippedExisting()).isEqualTo(1);
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.plusDays(30));
        verify(planService, never()).createScheduledPlan(any(), any(), any(), eq(TODAY.minusDays(60)), any());
    }

    /** Catch-up is bounded: a pathologically overdue schedule cannot create unbounded work in one pass. */
    @Test
    void runDueSchedules_extremelyOverdue_stopsAtTheCatchUpCeilingAndResumesNextPass() {
        LocalDate firstMissed = TODAY.minusDays(1_000);
        CycleCountSchedule due = CycleCountSchedule.builder()
                .scheduleId(SCHEDULE_ID)
                .locationId(LOCATION_ID)
                .zoneId(ZONE_ID)
                .frequencyDays(1)
                .nextDueDate(firstMissed)
                .autoCreatePlan(true)
                .active(true)
                .createdBy("user-1")
                .build();
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));
        when(planRepository.existsByScheduleIdAndDueDate(eq(SCHEDULE_ID), any()))
                .thenReturn(false);
        when(planService.createScheduledPlan(eq(SCHEDULE_ID), eq(LOCATION_ID), eq(List.of(ZONE_ID)), any(), any()))
                .thenReturn(CycleCountPlanResponse.builder().build());

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getPlansCreated()).isEqualTo(CATCH_UP_CEILING);
        // Still overdue, so the remaining boundaries are picked up by the next pass rather than lost.
        assertThat(due.getNextDueDate()).isEqualTo(firstMissed.plusDays(CATCH_UP_CEILING));
        assertThat(due.getNextDueDate()).isBeforeOrEqualTo(TODAY);
    }

    /** A non-positive frequency would loop forever during catch-up; it must be rejected, not run. */
    @Test
    void runDueSchedules_nonPositiveFrequency_isSkippedWithoutAdvancing() {
        CycleCountSchedule due = CycleCountSchedule.builder()
                .scheduleId(SCHEDULE_ID)
                .locationId(LOCATION_ID)
                .zoneId(ZONE_ID)
                .frequencyDays(0)
                .nextDueDate(TODAY.minusDays(10))
                .autoCreatePlan(true)
                .active(true)
                .createdBy("user-1")
                .build();
        when(scheduleRepository.findByActiveTrueAndNextDueDateLessThanEqualOrderByNextDueDateAsc(TODAY))
                .thenReturn(List.of(due));

        CycleCountScheduleRunResultResponse result = service.runDueSchedules();

        assertThat(result.getPlansCreated()).isZero();
        assertThat(due.getNextDueDate()).isEqualTo(TODAY.minusDays(10));
        verify(planService, never()).createScheduledPlan(any(), any(), any(), any(), any());
    }
}
