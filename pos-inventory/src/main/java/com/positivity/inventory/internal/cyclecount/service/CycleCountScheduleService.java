package com.positivity.inventory.internal.cyclecount.service;

import com.positivity.inventory.internal.dto.cyclecount.schedule.CreateCycleCountScheduleRequest;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleRunResultResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.UpdateCycleCountScheduleRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Recurring cycle-count schedules (odoo-parity I1, issue #1031). */
public interface CycleCountScheduleService {

    @NonNull
    CycleCountScheduleResponse createSchedule(
            @NonNull CreateCycleCountScheduleRequest request, @NonNull String createdBy);

    @NonNull
    CycleCountScheduleResponse getSchedule(@NonNull UUID scheduleId);

    /**
     * Lists one page of schedules, newest first. Null filters are ignored;
     * {@code dueOnly = true} restricts to active schedules whose next due date
     * has arrived (the "due for count" view).
     */
    @NonNull
    List<CycleCountScheduleResponse> listSchedules(
            UUID locationId, Boolean active, boolean dueOnly, int page, int size);

    @NonNull
    CycleCountScheduleResponse updateSchedule(
            @NonNull UUID scheduleId, @NonNull UpdateCycleCountScheduleRequest request);

    /** Soft delete: marks the schedule inactive; the row is never removed. */
    @NonNull
    CycleCountScheduleResponse deactivateSchedule(@NonNull UUID scheduleId);

    /**
     * Processes every active schedule whose next due date has arrived:
     * auto-create schedules get exactly one plan per due date and advance
     * {@code nextDueDate} by {@code frequencyDays}; view-only schedules are
     * left untouched (they surface via the due view instead).
     */
    @NonNull
    CycleCountScheduleRunResultResponse runDueSchedules();
}
